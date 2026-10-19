# Droits du serveur : Systems Manager (administration sans SSH), lecture de la configuration, écriture des sauvegardes,
# lecture de SES secrets uniquement.

data "aws_iam_policy_document" "ec2_confiance" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "passerelle" {
  name_prefix        = "${var.name}-"
  assume_role_policy = data.aws_iam_policy_document.ec2_confiance.json
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.passerelle.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "passerelle" {
  statement {
    sid       = "LireConfiguration"
    actions   = ["s3:GetObject", "s3:GetObjectVersion"]
    resources = ["${aws_s3_bucket.config.arn}/*"]
  }
  statement {
    sid       = "ListerConfiguration"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.config.arn]
  }
  statement {
    sid       = "EcrireSauvegardes"
    actions   = ["s3:PutObject", "s3:GetObject"]
    resources = ["${aws_s3_bucket.sauvegardes.arn}/*"]
  }
  statement {
    sid       = "ListerSauvegardes"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.sauvegardes.arn]
  }
  statement {
    sid       = "LireSesSecrets"
    actions   = ["ssm:GetParameter", "ssm:GetParametersByPath"]
    resources = ["arn:aws:ssm:${var.region}:${data.aws_caller_identity.moi.account_id}:parameter/${var.name}/*"]
  }
}

resource "aws_iam_role_policy" "passerelle" {
  name   = "passerelle"
  role   = aws_iam_role.passerelle.id
  policy = data.aws_iam_policy_document.passerelle.json
}

resource "aws_iam_instance_profile" "passerelle" {
  name_prefix = "${var.name}-"
  role        = aws_iam_role.passerelle.name
}
