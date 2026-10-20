# Secrets de Grafana, conservés chiffrés dans AWS Systems Manager Parameter Store.
# Les mots de passe des ENSEIGNES n'y sont pas : ils sont créés sur le serveur (scripts/enseigne-ajouter.sh), seule leur empreinte
# bcrypt est conservée, dans caddy/enseignes.caddy (sauvegardé dans S3).

resource "terraform_data" "verifications" {
  lifecycle {
    precondition {
      condition     = var.grafana_mode != "sso" || (var.sso_client_id != "" && var.sso_client_secret != "" && var.sso_domaines != "" && var.domaine_grafana != "")
      error_message = "grafana_mode = \"sso\" exige domaine_grafana, sso_client_id, sso_client_secret et sso_domaines."
    }
    precondition {
      condition     = !var.activer_glpi || (var.domaine_glpi != "" && var.domaine_glpi != var.domaine_metrics && var.domaine_glpi != var.domaine_logs)
      error_message = "activer_glpi = true exige domaine_glpi, différent de domaine_metrics et domaine_logs."
    }
    precondition {
      condition     = !var.activer_rustdesk || (var.domaine_rustdesk != "" && !contains([var.domaine_metrics, var.domaine_logs, var.domaine_glpi, var.domaine_grafana], var.domaine_rustdesk))
      error_message = "activer_rustdesk = true exige domaine_rustdesk, différent des autres noms de domaine."
    }
    precondition {
      condition     = var.domaine_metrics != var.domaine_logs
      error_message = "domaine_metrics et domaine_logs doivent être deux noms différents."
    }
  }
}

resource "random_password" "grafana_admin" {
  length  = 28
  special = false # alphanumérique : sans risque dans un fichier .env
}

resource "aws_ssm_parameter" "grafana_admin_password" {
  name       = "/${var.name}/grafana_admin_password"
  type       = "SecureString"
  value      = random_password.grafana_admin.result
  depends_on = [terraform_data.verifications]
}

resource "aws_ssm_parameter" "sso_client_secret" {
  count      = var.grafana_mode == "sso" ? 1 : 0
  name       = "/${var.name}/sso_client_secret"
  type       = "SecureString"
  value      = var.sso_client_secret
  depends_on = [terraform_data.verifications]
}

# --- GLPI (seulement si activer_glpi) : mots de passe générés, jamais saisis ni affichés ---

resource "random_password" "glpi" {
  for_each = var.activer_glpi ? toset(["db", "root", "admin"]) : toset([])
  length   = 28
  special  = false
}

resource "aws_ssm_parameter" "glpi" {
  for_each   = random_password.glpi
  name       = "/${var.name}/${each.key == "db" ? "glpi_db_password" : each.key == "root" ? "glpi_db_root_password" : "glpi_admin_password"}"
  type       = "SecureString"
  value      = each.value.result
  depends_on = [terraform_data.verifications]
}

# --- RustDesk (seulement si activer_rustdesk) : secret d'où sont dérivés les mots de passe de prise en main des caisses ---

resource "random_password" "rustdesk_secret" {
  count   = var.activer_rustdesk ? 1 : 0
  length  = 40
  special = false
}

resource "aws_ssm_parameter" "rustdesk_secret" {
  count      = var.activer_rustdesk ? 1 : 0
  name       = "/${var.name}/rustdesk_secret"
  type       = "SecureString"
  value      = random_password.rustdesk_secret[0].result
  depends_on = [terraform_data.verifications]
}
