# Si AWS détecte une panne matérielle du serveur, il le relance automatiquement (même disque, même IP, mêmes données).
# Le reste de la supervision (disque, mémoire, alertes des caisses) est assuré par Prometheus/Alertmanager.
# Surveillance EXTERNE conseillée en plus : une sonde sur https://<domaine_metrics>/healthz (voir guides/GUIDE-SERVEUR.md § 9).

resource "aws_sns_topic" "alertes" {
  count = var.alert_email == "" ? 0 : 1
  name  = "${var.name}-alertes"
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.alert_email == "" ? 0 : 1
  topic_arn = aws_sns_topic.alertes[0].arn
  protocol  = "email"
  endpoint  = var.alert_email
}

resource "aws_cloudwatch_metric_alarm" "recuperation_automatique" {
  alarm_name          = "${var.name}-panne-materielle"
  alarm_description   = "Panne du système sous-jacent : relance automatique de l'instance"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_System"
  dimensions          = { InstanceId = aws_instance.passerelle.id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  alarm_actions = compact([
    "arn:aws:automate:${var.region}:ec2:recover",
    var.alert_email == "" ? "" : aws_sns_topic.alertes[0].arn,
  ])
}

resource "aws_cloudwatch_metric_alarm" "instance_bloquee" {
  count               = var.alert_email == "" ? 0 : 1
  alarm_name          = "${var.name}-instance-bloquee"
  alarm_description   = "Le système d'exploitation ne répond plus (contrôle d'instance en échec) : à examiner"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_Instance"
  dimensions          = { InstanceId = aws_instance.passerelle.id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = [aws_sns_topic.alertes[0].arn]
}

# Budget d'alerte (optionnel) : prevenu par e-mail avant de depasser le plafond.
resource "aws_budgets_budget" "mensuel" {
  count        = var.budget_mensuel_usd > 0 && var.alert_email != "" ? 1 : 0
  name         = "${var.name}-mensuel"
  budget_type  = "COST"
  limit_amount = tostring(var.budget_mensuel_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  dynamic "notification" {
    for_each = [
      { seuil = 50, type = "ACTUAL" },
      { seuil = 90, type = "ACTUAL" },
      { seuil = 100, type = "FORECASTED" },
    ]
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value.seuil
      threshold_type             = "PERCENTAGE"
      notification_type          = notification.value.type
      subscriber_email_addresses = [var.alert_email]
    }
  }
}
