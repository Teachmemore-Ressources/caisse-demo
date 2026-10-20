output "ip_publique" {
  description = "Adresse IP fixe du serveur."
  value       = aws_eip.passerelle.public_ip
}

output "enregistrements_dns" {
  description = "Enregistrements A à créer chez votre registrar (déjà faits si route53_zone_id est renseigné)."
  value       = { for n in local.noms_dns : n => aws_eip.passerelle.public_ip }
}

output "instance_id" {
  value = aws_instance.passerelle.id
}

output "bucket_configuration" {
  value = aws_s3_bucket.config.id
}

output "bucket_sauvegardes" {
  value = aws_s3_bucket.sauvegardes.id
}

output "ouvrir_une_session" {
  description = "Console d'administration sur le serveur (sans SSH). Nécessite l'AWS CLI et le plugin Session Manager."
  value       = "aws ssm start-session --region ${var.region} --target ${aws_instance.passerelle.id}"
}

output "tunnel_grafana" {
  description = "Mode prive : ouvre Grafana sur http://localhost:3000 depuis votre poste."
  value       = var.grafana_mode == "prive" ? "aws ssm start-session --region ${var.region} --target ${aws_instance.passerelle.id} --document-name AWS-StartPortForwardingSession --parameters portNumber=3000,localPortNumber=3000" : "Mode sso : https://${var.domaine_grafana}"
}

output "mot_de_passe_grafana" {
  description = "Commande pour lire le mot de passe du compte administrateur Grafana."
  value       = "aws ssm get-parameter --region ${var.region} --name /${var.name}/grafana_admin_password --with-decryption --query Parameter.Value --output text"
}

output "ajouter_une_enseigne" {
  description = "À lancer dans la session sur le serveur."
  value       = "cd /opt/passerelle && sudo bash scripts/enseigne-ajouter.sh nomclient"
}

output "appliquer_la_configuration" {
  description = "Après un nouvel `apply` qui a modifié des fichiers de ../serveur : pousse la configuration sur le serveur."
  value       = "aws ssm send-command --region ${var.region} --instance-ids ${aws_instance.passerelle.id} --document-name AWS-RunShellScript --parameters commands=passerelle-sync"
}

output "glpi" {
  description = "Inventaire GLPI : commandes d'initialisation, d'accès et mot de passe administrateur."
  value = var.activer_glpi ? {
    initialiser         = "Dans la session sur le serveur : cd /opt/passerelle && sudo bash scripts/glpi-initialiser.sh"
    tunnel              = "aws ssm start-session --region ${var.region} --target ${aws_instance.passerelle.id} --document-name AWS-StartPortForwardingSession --parameters portNumber=8081,localPortNumber=8081"
    tunnel_adresse      = "http://localhost:8081 (compte : glpi)"
    mot_de_passe        = "aws ssm get-parameter --region ${var.region} --name /${var.name}/glpi_admin_password --with-decryption --query Parameter.Value --output text"
    url_des_agents      = "https://${var.domaine_glpi}/front/inventory.php"
    inscrire_une_caisse = "sudo storepos-kiosk-glpi-enroll --server-url https://${var.domaine_glpi}/front/inventory.php"
  } : { etat = "GLPI désactivé (activer_glpi = false)" }
}

output "rustdesk" {
  description = "Prise en main à distance : clé du serveur, mot de passe d'une caisse, adresse DNS à créer."
  value = var.activer_rustdesk ? {
    dns             = "Enregistrement A ${var.domaine_rustdesk} vers ${aws_eip.passerelle.public_ip} (Cloudflare : nuage gris, « DNS only »)"
    cle_publique    = "Dans la session sur le serveur : cd /opt/passerelle && sudo bash scripts/rustdesk-cle.sh"
    mot_de_passe    = "Dans la session sur le serveur : cd /opt/passerelle && sudo bash scripts/rustdesk-mdp.sh <ID RustDesk de la caisse>"
    inscrire_caisse = "sudo storepos-kiosk-remote-enroll --server ${var.domaine_rustdesk} --key '<cle publique>' --secret-file /chemin/du/secret"
    secret          = "aws ssm get-parameter --region ${var.region} --name /${var.name}/rustdesk_secret --with-decryption --query Parameter.Value --output text"
  } : { etat = "RustDesk désactivé (activer_rustdesk = false)" }
}
