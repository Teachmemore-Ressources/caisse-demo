terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # L'état Terraform contient des secrets (mot de passe Grafana généré, secret SSO) : pour un usage d'équipe,
  # le stocker dans un bucket S3 chiffré à accès restreint. Exemple (à créer À LA MAIN, pas par ce code) :
  #
  # backend "s3" {
  #   bucket       = "mon-etat-terraform"
  #   key          = "passerelle-caisses/terraform.tfstate"
  #   region       = "eu-west-3"
  #   encrypt      = true
  #   use_lockfile = true
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = merge({
      Projet    = var.name
      GereParTf = "oui"
    }, var.tags)
  }
}
