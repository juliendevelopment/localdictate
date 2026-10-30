# Périmètre

## Demande validée

- Français et anglais ; pas de traduction demandée.
- Autorisation utilisateur pour affichage au-dessus des applications et accessibilité.
- Dictée jusqu’à **15 minutes maximum**, sans coupure à chaque pause ; arrêt explicite puis insertion à l’emplacement du curseur, en conservant le clavier existant.
- Mode distinct pour enregistrer une **réunion jusqu’à 2 heures**, puis transcrire et synthétiser les textes.
- Modèle local, pas d’abonnement ni d’API payante ; préparation initiale des modèles autorisée, contenu toujours local.

« Synthétiser » est interprété ici comme produire un **résumé écrit du contenu**, pas une synthèse vocale. Le format du résumé, le choix manuel/automatique de langue et le niveau de détail restent à préciser sans bloquer le socle ni la dictée.

## Ordre de livraison

1. **Socle** : dépôt privé, projet Android, présentation distincte des modes, limites et tests, CI sur `main`.
2. **Dictée fonctionnelle** : préparer un modèle FR/EN vérifié, capturer en foreground, segmenter sans perdre de mots, arrêter proprement ; bulle et insertion sûre avec repli explicite.
3. **Durcissement dictée** : interruptions, refus/révocation de permissions, changement de cible, récupération de texte, 15 min sur Pixel, mémoire/latence/thermique/confidentialité.
4. **Réunion** : capture durable et bornée en mémoire, reprise/arrêt, transcription progressive conservée, export local explicite ; validation d’une session de 2 h.
5. **Résumé local de réunion** : moteur séparé, découpage hiérarchique sans tronquer les 2 h, traçabilité vers la transcription, qualité FR/EN et performances Pixel. Ne pas confondre moteur ASR et modèle de résumé.

Les deux modes font partie de la cible ; la priorité d’implémentation est la dictée. Le résumé de réunion n’est pas retiré du périmètre ni déclaré déjà disponible.

## Implémentation de cette lane

La chaîne dictée réelle est raccordée : préparation Parakeet/Silero, service microphone armé depuis l’activité visible, bulle, capture/segmentation/reconnaissance, transcription privée et insertion sous conditions. La réunion reste indisponible. Les cases ci-dessous restent des critères **téléphone** : elles ne sont pas cochées par des fixtures JVM.

L’insertion automatique passe par la connexion d’édition d’accessibilité sur API 33+, sans presse-papiers ni remplacement de Gboard. Elle requiert une cible et une session inchangées, un contexte complet observable et aucune composition active ou inconnue. Sur API 29–32 ou un éditeur incompatible, le texte reste disponible pour copie manuelle explicite. Voir [l’architecture](architecture.md) et [la checklist Pixel](acceptation-pixel.md). Le son en RAM reste perdu après mort du processus.

## Acceptation dictée

- [ ] Une bulle non focusable conserve la cible de saisie pendant le contrôle de l’enregistrement.
- [ ] Permission microphone et service foreground microphone, notification/arrêt visibles ; rien ne démarre à l’ouverture de l’app.
- [ ] FR/EN reconnus par un vrai modèle, sans appel réseau pendant l’usage.
- [ ] Une pause n’arrête pas la session ; une limite atteinte arrête la capture puis finalise le texte accepté sans troncature silencieuse.
- [ ] Insertion au curseur ou remplacement de la seule sélection explicitement ciblée ; le reste du champ est conservé.
- [ ] Mot de passe/champ incompatible, cible changée ou application fermée : aucun écrasement, texte récupérable, repli explicite.
- [ ] Une session de 15 min reste bornée en RAM et ne perd pas les segments acceptés.
- [ ] Validations réelles sur le Pixel, au minimum dans quelques applications natives et un champ web ; pas de promesse « toutes les apps ».

## Acceptation réunion

- [ ] Démarrage volontaire et signal visible ; l’utilisateur gère l’accord des participants.
- [ ] Capture 2 h, fichiers privés et écritures progressives, arrêt sûr si stockage insuffisant/interruption.
- [ ] Transcription conservée même si le résumé échoue ; export/suppression explicites.
- [ ] Aucun collage dans une autre application et aucune identification de locuteurs promise sans implémentation/test.
- [ ] Résumé local distinguant faits, décisions et actions lorsqu’ils existent, sans les inventer ; accès à la transcription complète.
- [ ] Essai réel d’endurance, stockage, RAM, chaleur et délai de traitement sur le téléphone.

## Limites de confidentialité

Absence d’analytics et inspection des dépendances/manifest sont des preuves statiques. Le mode avion seul ne prouve pas l’absence de collecte différée : les tests réseau doivent également observer le retour de connectivité. Ne jamais présenter des tests hôte comme des mesures Pixel.
