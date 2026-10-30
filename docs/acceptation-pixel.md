# Recette manuelle Pixel — non exécutée

Installer d’abord un APK signé par l’agent ; tester debug et release minifiée. Noter version Android, appareil, SHA de l’APK, durée, app cible et résultat. Ne jamais enregistrer de contenu privé dans les logs du test.

- [ ] Ouverture neuve : aucun indicateur micro, service, téléchargement ni permission automatique. Réunion clairement indisponible.
- [ ] Préparer volontairement, observer progression ; annuler en plein transfert, reprendre après réseau coupé ; contrôler espace insuffisant/erreur, installation complète et redémarrage hors ligne.
- [ ] Refuser/accorder micro, notifications, overlay et accessibilité ; retour des réglages sans armement automatique. Tester révocation pendant armement/capture/traitement.
- [ ] Armer depuis écran visible : notification immédiatement, micro fermé pendant chargement puis attente. Passer dans une autre app : toucher/drag/croix de la bulle ne vole pas le focus ; la capture commence uniquement au toucher Dicter.
- [ ] FR puis EN, langue automatique, ponctuation ; longues pauses sans terminer la session, arrêt explicite, vidage complet. Mesurer qualité et délai ; aucun résultat natif établi par Robolectric.
- [ ] 15 min avec pauses et parole continue : arrêt exact du micro à 900 s capturées, pas de PCM complet en RAM, segments finaux traités ; mesurer pic RAM, retard, chaleur, batterie et temps final. Vérifier écran éteint/verrouillage, appel et autre app utilisant le micro.
- [ ] Forcer une surcharge : arrêt immédiat du micro, erreur visible dans bulle/notification/app ; texte accepté vidé, aucune perte silencieuse ni insertion automatique du résultat incomplet.
- [ ] Champ natif Notes, messagerie et champ web : curseur milieu/début/fin, sélection originale ; texte extérieur à la sélection intact. Vérifier le résultat observé, sans confondre dispatch et insertion.
- [ ] Vérifier précisément le refus Android de lecture du presse-papiers depuis la bulle sans focus : collage annulé avec explication, ouvrir LocalDictate, Copier puis coller manuellement. Aucun contournement privilégié. Le clip remplace l’ancien contenu et est marqué sensible ; s’il a été remplacé par l’utilisateur, aucun nettoyage de sa nouvelle copie.
- [ ] Changer d’app/fenêtre/champ/contenu/sélection puis revenir pendant la dictée ; fermer la cible ; champ password/visible password ; désactiver accessibilité : aucun collage aveugle, texte toujours disponible.
- [ ] Rotation/recréation activité pendant enregistrement/traitement : pas de seconde session ni insertion double. Tuer le processus/service, rouvrir : texte déjà reconnu récupéré comme partiel, pas de redémarrage micro/bulle ni collage ; segments non reconnus signalés comme perdus.
- [ ] Après préparation : mode avion puis pare-feu/observation réseau pendant armement, capture, traitement, récupération et retour de connectivité. Aucun upload, analytics ou télémétrie différée. Le mode avion seul ne prouve pas ce dernier point.

Prochaine décision après recette : l’annulation du collage automatique imposée par la vérification du presse-papiers est-elle acceptable pour le mainteneur, ou faut-il concevoir une confirmation utilisateur distincte ? Ne pas affaiblir la conservation du texte et du presse-papiers pour masquer cette limite.
