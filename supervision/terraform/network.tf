# Réseau minimal : un VPC, un sous-réseau public, une adresse IP fixe (Elastic IP).
# Les caisses se connectent en HTTPS (443) ; AUCUN port SSH n'est ouvert : l'administration passe par AWS Systems Manager.

locals {
  az = "${var.region}${var.availability_zone_suffix}"
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = var.name }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = var.name }
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.this.id
  cidr_block              = var.vpc_cidr
  availability_zone       = local.az
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name}-public" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }
  tags = { Name = "${var.name}-public" }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "passerelle" {
  name_prefix = "${var.name}-"
  description = "Passerelle de supervision - HTTP et HTTPS entrants seulement, pas de SSH"
  vpc_id      = aws_vpc.this.id

  # 80 : défi Let's Encrypt et redirection vers HTTPS. 443 : caisses (envoi) et Grafana en mode SSO.
  # Pas de filtrage par adresse : les caisses sont derrière des box ADSL dont l'adresse change et se partage.
  ingress {
    description = "HTTP - certificats et redirection"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "HTTPS - caisses et Grafana en mode SSO"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  # HTTP/3 (QUIC) : Caddy le propose ; sans cette règle les clients retombent simplement sur HTTPS/TCP.
  ingress {
    description = "HTTP/3"
    from_port   = 443
    to_port     = 443
    protocol    = "udp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Sorties libres - Lets Encrypt, Docker, S3, Systems Manager, mises a jour"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  lifecycle {
    create_before_destroy = true
  }
  tags = { Name = var.name }
}

resource "aws_eip" "passerelle" {
  domain = "vpc"
  tags   = { Name = var.name }
}

resource "aws_eip_association" "passerelle" {
  instance_id   = aws_instance.passerelle.id
  allocation_id = aws_eip.passerelle.id
}

# --- DNS (optionnel) ---------------------------------------------------------------------------------------------

locals {
  noms_dns = compact([
    var.domaine_metrics,
    var.domaine_logs,
    var.grafana_mode == "sso" ? var.domaine_grafana : "",
    var.activer_glpi ? var.domaine_glpi : "",
  ])
}

resource "aws_route53_record" "passerelle" {
  for_each = var.route53_zone_id == "" ? toset([]) : toset(local.noms_dns)

  zone_id = var.route53_zone_id
  name    = each.value
  type    = "A"
  ttl     = 300
  records = [aws_eip.passerelle.public_ip]
}
