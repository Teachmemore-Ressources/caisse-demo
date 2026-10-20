# Raccourcis du dépôt (machine Linux). « make » seul affiche l'aide.
.PHONY: aide tests tests-complet app kiosque agent livrables terraform-plan nettoyer
SHELL := /bin/bash

aide:
	@echo "make tests           tests rapides de la supervision (configs, alertes, agent, scripts, Terraform hors ligne)"
	@echo "make tests-complet   + bout en bout avec les vrais composants (long)"
	@echo "make app             tests + .deb de l'application de caisse"
	@echo "make kiosque         .deb du mode kiosque"
	@echo "make agent           .deb de l'agent de supervision (et remote / GLPI)"
	@echo "make livrables       range les derniers .deb dans livrables/ (retire les anciennes versions)"
	@echo "make terraform-plan  plan Terraform (ne modifie rien)"
	@echo "make nettoyer        supprime les fichiers générés (build/, __pycache__)"

tests:
	bash supervision/tests/lancer-tout.sh

tests-complet:
	bash supervision/tests/lancer-tout.sh --complet

app:
	bash tools/wsl-build.sh test
	bash tools/wsl-build.sh createDistributable

kiosque:
	python3 storepos-kiosk/build-deb.py

agent:
	python3 tools/pkg_builder.py supervision/agent/pkg supervision/agent/dist
	python3 tools/pkg_builder.py supervision/remote-agent/pkg supervision/remote-agent/dist
	python3 tools/pkg_builder.py supervision/glpi-agent/pkg supervision/glpi-agent/dist

# Garde seulement la version la plus récente de chaque paquet dans livrables/
livrables:
	@mkdir -p livrables
	@for f in caisse-demo_*_amd64.deb storepos-kiosk/storepos-kiosk_*_all.deb supervision/*/dist/*.deb; do \
	  [ -f "$$f" ] && mv -v "$$f" livrables/ ; done; true
	@cd livrables && for p in $$(ls *.deb | sed 's/_.*//' | sort -u); do \
	  ls -1 $${p}_*.deb 2>/dev/null | sort -V | head -n -1 | xargs -r rm -v ; done; ls -l

terraform-plan:
	cd supervision/terraform && terraform plan

nettoyer:
	rm -rf build
	find . -name __pycache__ -not -path './.git/*' -not -path '*/.terraform/*' -prune -exec rm -rf {} +
