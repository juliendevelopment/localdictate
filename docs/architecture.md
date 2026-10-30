# Architecture cible et socle livré

## Livré dans le socle

`MainActivity` Compose présente les deux modes et conserve le choix lors d’une recréation de l’activité. `SessionMode` définit les durées et l’éligibilité à l’insertion/résumé ; `SessionBudget` vérifie un temps écoulé monotone fourni par l’appelant. Il n’y a encore ni capture, ni moteur de modèle, ni accessibilité enregistrée.

## Pipeline dictée à construire

Geste utilisateur → permissions et démarrage Android autorisé → service foreground microphone → AudioRecord → file PCM bornée → VAD/segments → moteur ASR local FR/EN → transcription agrégée → arrêt et vidage des segments acceptés → insertion explicite dans la cible validée.

- Le VAD segmente, mais ne décide pas seul de terminer la session.
- Le moteur peut traiter pendant l’écoute ; ne pas attendre l’arrêt pour charger 15 min de PCM en mémoire.
- Contrôle flottant non focusable ; vérifier les restrictions de lancement du microphone sur les versions Android ciblées au lieu de supposer qu’une permission overlay suffit.
- L’accessibilité ne sert qu’à la cible de saisie et à une action utilisateur. Vérifier identifiant de fenêtre/app, éditabilité, sélection et capacité d’action au moment de l’insertion. Une API `ACTION_SET_TEXT` remplace le champ entier : l’utiliser naïvement ferait perdre le texte existant.
- Préférer une insertion exposée par le champ, sans reconstruction destructive de texte. Un échec de collage doit être signalé ; ne pas annoncer l’insertion sur la seule demande envoyée.
- Le presse-papiers peut exposer le texte à d’autres composants : limiter son usage, signaler le contenu sensible et ne jamais l’effacer plus tard si l’utilisateur l’a remplacé.

## Pipeline réunion à construire

Geste utilisateur → capture foreground → journal/fichiers locaux incrémentaux → transcription horodatée par segments → finalisation → résumé hiérarchique local séparé → consultation/export volontaire.

Ne pas garder 2 h d’audio en RAM, ni tronquer le document à la fenêtre de contexte d’un modèle de résumé. Un problème de synthèse ne supprime pas l’audio ou la transcription. Politique de conservation de l’audio et reprise après interruption à valider avant la livraison de ce mode.

## Modèles

un projet voisin constitue une référence de composants, pas un catalogue à copier aveuglément. Un moteur ASR local multilingue et un VAD sont nécessaires ; sherpa-onnx/Parakeet et whisper.cpp sont des pistes, **aucun moteur ni modèle n’est sélectionné/intégré dans ce socle**. Inspecter les vrais artefacts, licences, ABI et API avant intégration. Télécharger uniquement à la demande, vérifier tailles/digests et installer atomiquement.

Le résumé requiert sa propre solution locale. Qualité, licence, taille, mémoire et vitesse sur Pixel restent à mesurer ; ne pas promettre la fluidité d’un modèle PC ni une accélération NPU non vérifiée.

## Sécurité

Stockage privé, sauvegarde Android/transfert de données désactivés pour les futurs contenus sensibles ; pas de logs audio/transcription. Aucun réseau dans le socle. Toute future permission réseau est justifiée uniquement par la préparation des modèles, avec audit de dépendances et des appels pendant/après l’écoute.
