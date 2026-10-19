data "aws_ssm_parameter" "ubuntu_ami" {
  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id"
}

locals {
  # Variables lues par files/bootstrap.sh (écrites dans /etc/passerelle.env). Pas de secret ici : ils sont lus dans Parameter Store.
  user_data = join("\n", [
    templatefile("${path.module}/files/user-data-entete.sh.tftpl", {
      region          = var.region
      nom             = var.name
      config_bucket   = aws_s3_bucket.config.id
      backup_bucket   = aws_s3_bucket.sauvegardes.id
      volume_id       = aws_ebs_volume.donnees.id
      mode            = var.grafana_mode
      acme_email      = var.acme_email
      domaine_metrics = var.domaine_metrics
      domaine_logs    = var.domaine_logs
      domaine_grafana = var.grafana_mode == "sso" ? var.domaine_grafana : ""
      grafana_user    = var.grafana_admin_user
      sso_client_id   = var.sso_client_id
      sso_domaines    = var.sso_domaines
      glpi_active     = var.activer_glpi ? "1" : "0"
      domaine_glpi    = var.activer_glpi ? var.domaine_glpi : ""
    }),
    file("${path.module}/files/bootstrap.sh"),
  ])
}

resource "aws_instance" "passerelle" {
  ami                    = data.aws_ssm_parameter.ubuntu_ami.value
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.passerelle.id]
  iam_instance_profile   = aws_iam_instance_profile.passerelle.name

  associate_public_ip_address = true # remplacée par l'Elastic IP juste après la création
  disable_api_termination     = !var.environnement_test
  monitoring                  = true

  metadata_options {
    http_tokens                 = "required" # IMDSv2 seulement
    http_put_response_hop_limit = 2          # les conteneurs Docker peuvent atteindre les métadonnées si besoin
  }

  root_block_device {
    volume_size = var.root_volume_gb
    volume_type = "gp3"
    encrypted   = true
  }

  user_data = local.user_data
  # Modifier le script de démarrage ne doit pas détruire un serveur qui porte vos données : les mises à jour de configuration
  # passent par S3 + `passerelle-sync` (voir README).
  user_data_replace_on_change = false

  depends_on = [
    aws_s3_object.serveur,
    aws_s3_object.outils,
    aws_ssm_parameter.grafana_admin_password,
    aws_ssm_parameter.sso_client_secret,
    aws_ssm_parameter.glpi,
    aws_iam_role_policy.passerelle,
  ]

  lifecycle {
    ignore_changes = [ami, user_data]
  }

  tags = { Name = var.name }
}
