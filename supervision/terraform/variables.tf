variable "region" {
  description = "Région AWS (eu-west-3 = Paris)."
  type        = string
  default     = "eu-west-3"
}

variable "availability_zone_suffix" {
  description = "Lettre de la zone de disponibilité (a, b ou c) : le serveur et son disque de données y sont placés."
  type        = string
  default     = "a"
}

variable "name" {
  description = "Préfixe des ressources (minuscules, chiffres, tirets)."
  type        = string
  default     = "passerelle-caisses"
  validation {
    condition     = can(regex("^[a-z0-9-]{3,30}$", var.name))
    error_message = "name : 3 à 30 caractères, minuscules, chiffres et tirets."
  }
}

variable "instance_type" {
  description = "Type d'instance, processeur x86_64 obligatoire. Essai : t3.medium (2 vCPU, 4 Go, ~35 EUR/mois tout compris). Production de milliers de caisses : c6i.xlarge ou plus."
  type        = string
  default     = "t3.medium"
}

variable "root_volume_gb" {
  description = "Taille du disque système."
  type        = number
  default     = 30
}

variable "data_volume_gb" {
  description = "Taille du disque de données (Docker : Prometheus, Loki, Grafana, certificats, sauvegardes locales). Prometheus est plafonné à 40 Go."
  type        = number
  default     = 30
}

# --- Noms DNS -----------------------------------------------------------------------------------------------------

variable "domaine_metrics" {
  description = "Nom DNS où les caisses envoient les mesures, ex. metrics.exemple.fr."
  type        = string
}

variable "domaine_logs" {
  description = "Nom DNS où les caisses envoient les journaux, ex. logs.exemple.fr."
  type        = string
}

variable "domaine_grafana" {
  description = "Nom DNS de Grafana (obligatoire en mode « sso », ignoré en mode « prive »)."
  type        = string
  default     = ""
}

variable "route53_zone_id" {
  description = "Identifiant de la zone Route 53 qui contient ces noms. Vide = aucun enregistrement créé : vous créez les A vous-même (voir les sorties)."
  type        = string
  default     = ""
}

variable "acme_email" {
  description = "Adresse qui reçoit les avis d'expiration Let's Encrypt."
  type        = string
}

# --- Protection de Grafana ----------------------------------------------------------------------------------------

variable "grafana_mode" {
  description = "« prive » : Grafana n'est jamais public (accès par tunnel AWS Systems Manager). « sso » : public, connexion Google + 2FA imposé côté Google."
  type        = string
  default     = "prive"
  validation {
    condition     = contains(["prive", "sso"], var.grafana_mode)
    error_message = "grafana_mode doit valoir \"prive\" ou \"sso\"."
  }
}

variable "grafana_admin_user" {
  type    = string
  default = "admin"
}

variable "sso_client_id" {
  description = "Mode sso : identifiant OAuth Google (application web)."
  type        = string
  default     = ""
}

variable "sso_client_secret" {
  description = "Mode sso : secret OAuth Google. Il sera dans l'état Terraform : protégez-le."
  type        = string
  default     = ""
  sensitive   = true
}

variable "sso_domaines" {
  description = "Mode sso : domaines d'e-mail autorisés, séparés par des virgules (ex. exemple.fr)."
  type        = string
  default     = ""
}

# --- Inventaire GLPI ----------------------------------------------------------------------------------------------

variable "activer_glpi" {
  description = "true : ajoute l'inventaire GLPI (GLPI + base MariaDB) sur le même serveur. Prévoir au moins t3.large (8 Go) avec Grafana, Prometheus et Loki."
  type        = bool
  default     = false
}

variable "domaine_glpi" {
  description = "Nom DNS où les caisses envoient leur inventaire, ex. glpi.exemple.fr (obligatoire si activer_glpi)."
  type        = string
  default     = ""
}

# --- Exploitation -------------------------------------------------------------------------------------------------

variable "alert_email" {
  description = "Adresse prévenue si AWS détecte une panne matérielle du serveur (et qu'il le relance). Vide = pas d'e-mail. Un e-mail de confirmation est envoyé."
  type        = string
  default     = ""
}

variable "retention_sauvegardes_jours" {
  description = "Durée de conservation des sauvegardes dans S3."
  type        = number
  default     = 30
}

variable "environnement_test" {
  description = "true (par defaut) : environnement d'ESSAI, `terraform destroy` supprime tout d'un coup, y compris les buckets S3 meme non vides et l'instance (pas de protection). PRODUCTION : mettre false."
  type        = bool
  default     = true
}

variable "budget_mensuel_usd" {
  description = "Budget d'alerte AWS (alerte a 50 % et 90 % du reel, 100 % de la prevision) envoye a alert_email. 0 = pas de budget. Necessite alert_email."
  type        = number
  default     = 0
}

variable "vpc_cidr" {
  type    = string
  default = "10.42.0.0/24"
}

variable "tags" {
  description = "Étiquettes supplémentaires."
  type        = map(string)
  default     = {}
}
