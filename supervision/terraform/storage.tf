# Stockage : deux buckets S3 privés (configuration livrée au serveur, sauvegardes), disque de données, instantanés quotidiens.

data "aws_caller_identity" "moi" {}

locals {
  suffixe = substr(data.aws_caller_identity.moi.account_id, -6, 6)
}

# --- Bucket « configuration » : le serveur y récupère les fichiers de ../serveur --------------------------------

resource "aws_s3_bucket" "config" {
  bucket        = "${var.name}-config-${local.suffixe}"
  force_destroy = var.environnement_test
}

resource "aws_s3_bucket_public_access_block" "config" {
  bucket                  = aws_s3_bucket.config.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "config" {
  bucket = aws_s3_bucket.config.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_versioning" "config" {
  bucket = aws_s3_bucket.config.id
  versioning_configuration { status = "Enabled" }
}

locals {
  dossier_serveur = "${path.module}/../serveur"
  # Tout le dossier serveur/ sauf l'ancienne piste Cloudflare (abandonnée). Les identifiants des enseignes ne sont JAMAIS
  # envoyés depuis ici : le fichier caddy/enseignes.caddy n'est qu'un modèle inutilisable, et le serveur ne l'écrase pas.
  fichiers_serveur = setsubtract(fileset(local.dossier_serveur, "**"), fileset(local.dossier_serveur, "cloudflare/**"))
}

resource "aws_s3_object" "serveur" {
  for_each = local.fichiers_serveur

  bucket = aws_s3_bucket.config.id
  key    = "serveur/${each.value}"
  source = "${local.dossier_serveur}/${each.value}"
  etag   = filemd5("${local.dossier_serveur}/${each.value}")
}

resource "aws_s3_object" "outils" {
  for_each = fileset("${path.module}/files", "*.sh")

  bucket = aws_s3_bucket.config.id
  key    = "outils/${each.value}"
  source = "${path.module}/files/${each.value}"
  etag   = filemd5("${path.module}/files/${each.value}")
}

# --- Bucket « sauvegardes » ---------------------------------------------------------------------------------------

resource "aws_s3_bucket" "sauvegardes" {
  bucket        = "${var.name}-sauvegardes-${local.suffixe}"
  force_destroy = var.environnement_test
}

resource "aws_s3_bucket_public_access_block" "sauvegardes" {
  bucket                  = aws_s3_bucket.sauvegardes.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "sauvegardes" {
  bucket = aws_s3_bucket.sauvegardes.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_versioning" "sauvegardes" {
  bucket = aws_s3_bucket.sauvegardes.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_lifecycle_configuration" "sauvegardes" {
  bucket     = aws_s3_bucket.sauvegardes.id
  depends_on = [aws_s3_bucket_versioning.sauvegardes]

  rule {
    id     = "rotation"
    status = "Enabled"
    filter {}
    expiration {
      days = var.retention_sauvegardes_jours
    }
    noncurrent_version_expiration {
      noncurrent_days = 7
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 3
    }
  }
}

# Refuse tout accès sans TLS aux deux buckets.
data "aws_iam_policy_document" "tls_seulement" {
  for_each = {
    config      = aws_s3_bucket.config.arn
    sauvegardes = aws_s3_bucket.sauvegardes.arn
  }
  statement {
    sid       = "RefuserSansTLS"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [each.value, "${each.value}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "config" {
  bucket     = aws_s3_bucket.config.id
  policy     = data.aws_iam_policy_document.tls_seulement["config"].json
  depends_on = [aws_s3_bucket_public_access_block.config]
}

resource "aws_s3_bucket_policy" "sauvegardes" {
  bucket     = aws_s3_bucket.sauvegardes.id
  policy     = data.aws_iam_policy_document.tls_seulement["sauvegardes"].json
  depends_on = [aws_s3_bucket_public_access_block.sauvegardes]
}

# --- Disque de données + instantanés quotidiens -------------------------------------------------------------------

resource "aws_ebs_volume" "donnees" {
  availability_zone = local.az
  size              = var.data_volume_gb
  type              = "gp3"
  encrypted         = true
  tags = {
    Name       = "${var.name}-donnees"
    Sauvegarde = var.name
  }
}

resource "aws_volume_attachment" "donnees" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.donnees.id
  instance_id = aws_instance.passerelle.id
}

data "aws_iam_policy_document" "dlm_confiance" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["dlm.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "dlm" {
  name_prefix        = "${var.name}-dlm-"
  assume_role_policy = data.aws_iam_policy_document.dlm_confiance.json
}

resource "aws_iam_role_policy_attachment" "dlm" {
  role       = aws_iam_role.dlm.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole"
}

resource "aws_dlm_lifecycle_policy" "donnees" {
  description        = "${var.name} - instantane quotidien du disque de donnees"
  execution_role_arn = aws_iam_role.dlm.arn
  state              = "ENABLED"

  policy_details {
    resource_types = ["VOLUME"]
    target_tags    = { Sauvegarde = var.name }

    schedule {
      name = "quotidien"
      create_rule {
        interval      = 24
        interval_unit = "HOURS"
        times         = ["02:00"]
      }
      retain_rule {
        count = 7
      }
      copy_tags = true
    }
  }
}
