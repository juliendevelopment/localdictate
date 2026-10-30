# Reprise LocalDictate — pause pour préserver les quotas

## Situation actuelle

le mainteneur demande de sauvegarder en commits pour confier la suite à un autre agent. **Les deux agents Codex ont été arrêtés volontairement. Ne pas les relancer automatiquement ni reprendre la publication sur leurs notifications de fin.** Le travail est conservé ; une reprise doit venir de l’agent choisi par le mainteneur.

**Aucun APK de dictée signé et prêt à tester n’a encore été publié.** Les APK de développement précédents ne constituent pas la livraison demandée. Les corrections/API33 et l’intégration Android réunion sont des **WIP**, pas des fonctionnalités validées en totalité.

Dépôt privé : https://github.com/juliendevelopment/localdictate . Checkout canonique : `~/dev/sources/localdictate`.

## Branches à récupérer

- **`main`** : socle Android/CI, première implémentation de dictée intégrée, signature Gradle optionnelle dédiée, version source `0.1.0` / code `100`, outillage de release et contrôle d’APK. La logique d’insertion de cette base utilise encore le presse-papiers : ne pas la publier comme solution finale.
- **`work/dictation`** — point de code WIP `714696d3e1c2b8bdcbd461ae50ac306351615400` : correction d’insertion directe via l’API d’édition d’accessibilité Android 13+, ressources/service spécifiques API33, isolation des anciens Android, microphone sensible et tests supplémentaires. **À revoir et valider avant fusion/release.** Worktree VPS : `<private folder>`.
- **`work/meeting-core`** — noyau validé `fd84d93`, puis point d’intégration WIP `d4083376741af126f3492eebaf314addc027b10b` : stockage audio/journal/résumé extractif plus contrôleur, pipeline, dépôt, service et export commencés. Le branchement UI/manifest/tests et les transitions doivent être terminés/revus ; ne pas supposer cette branche compilable ou complète. Worktree VPS : `<private folder>`.

Une entrée documentaire de reprise peut être au-dessus de ces points WIP. Les branches ne sont pas à jeter ou recréer : elles contiennent le travail à poursuivre. Les modifications de `MicrophoneCapture`, des contrôleurs et de l’UI doivent être réconciliées, pas résolues aveuglément par « ours/theirs ».

## Périmètre déjà validé par le mainteneur

- Français et anglais ; traitement local, sans cloud/API payante/analytics. Modèles préparés explicitement, jamais embarqués dans Git/APK.
- Dictée jusqu’à 15 min, sans arrêt sur silence : bulle, démarrage/arrêt utilisateur puis insertion au curseur ; overlay et accessibilité acceptés, Gboard conservé.
- Mode distinct : réunion jusqu’à 2 h, enregistrement puis transcription et résumé local. Le résumé actuellement développé est **extractif**, à partir de phrases originales horodatées, pas un LLM génératif.
- Objectif final : APK signé téléchargeable, que le mainteneur testera lui-même sur son Pixel. Le feu vert fonctionnel existait déjà ; la pause actuelle est liée au quota et au relais, pas à une question de périmètre en attente.

Voir [le périmètre](mvp.md), [l’architecture](architecture.md), [la CI](ci.md), `docs/meeting.md` sur sa branche et [la recette Pixel](acceptation-pixel.md).

## Preuves disponibles — ne pas les étendre aux WIP

- Première dictée : **76 tests JVM/activité**, sans échec/erreur/ignoré ; rapports initialement produits par la lane puis recomptés par le parent avant les corrections API33. Tests/lint/APK debug + release minifiée non signée avaient réussi. Historique détaillé : `git show e5d7db3:docs/status.md`.
- Noyau réunion pur : **36 tests JVM**, rerun indépendant du parent réussi, Kotlin 2.1.0 / JUnit 4.13.2, heap test 64 Mio. Voir `docs/meeting.md` dans `work/meeting-core` ; cela n’établit pas 2 h de capture sur téléphone.
- Outillage de release/inspection : **8 tests Python** réussis et workflows vérifiés par actionlint.
- Dernière CI distante verte vérifiée avant ces extensions : `302301b84b3c909daf0c1d6a48fbcdd70a0a0830`, https://github.com/juliendevelopment/localdictate/actions/runs/41108473585 . Elle ne valide pas les WIP ni la dictée complète.
- Les dernières sessions ont été interrompues volontairement. Leurs reçus de sortie non nulle ne sont pas un diagnostic de bug et ne doivent pas lancer un nouveau Codex automatiquement. **Rejouer les validations sur le code réconcilié.**

## Points de revue prioritaires

1. Android refuse généralement la lecture du presse-papiers sans focus avec un autre clavier. Ne pas contourner ce problème par une restauration/vidange aveugle ou un collage susceptible de remplacer la copie de l’utilisateur.
2. API33 : `AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR`, `AccessibilityService.getInputMethod()`, puis `AccessibilityInputConnection.commitText(text, 1, null)` sans remplacer Gboard. Vérifier le vrai contexte/éditeur et la sélection **après** vidage ASR. Une composition Gboard active peut être remplacée par commitText : elle doit être détectée/refusée. Aucune action destructive pour « réparer » une dérive.
3. Une seule tentative persistée ; vérifier fraîchement texte attendu et curseur. Une commande envoyée n’est pas une insertion observée. Cible/password/contexte ambigu : garder le texte et proposer Copier explicitement. Aucun nettoyage automatique du presse-papiers.
4. FGS microphone armé depuis Activity visible, micro fermé au repos, arrêt/revocation/mute/notification accessibles. Pas de service recréé silencieusement depuis une ancienne commande.
5. Réunion : un seul verrou d’usage partagé avec préparation/dictée ; audio app-private borné sur disque, capture d’abord puis ASR réel en flux, timestamps issus de l’audio. Ne pas perdre la transcription si résumé/export échoue ; aucune réparation de journal silencieuse.

## Reprise et livraison

Sur le VPS, poursuivre dans les worktrees existants ou fusionner les branches dans le checkout canonique après revue. Conserver les modèles/chemins, l’application `be.arte.localdictate`, la signature et les réglages. Ne pas réécrire l’historique ou publier les WIP comme release.

Validation locale, avec SDK existant et JDK17 :

```sh
cd ~/dev/sources/localdictate
export ANDROID_HOME=/path/to/android-sdk
export TMPDIR=$TMPDIR
./android/gradlew -p android --no-daemon --max-workers=1 --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
python3 -m unittest discover -s scripts -p 'test_*.py'
```

RAM/swap limitées : pas de builds/modèles en parallèle. Verrou partagé sur ce VPS : `$TMPDIR`. Le mode cache Robolectric optionnel est documenté dans `docs/ci.md`.

La clé dédiée existe **hors Git/vault** sous `<private key folder>`, permissions 0700/0600 ; secrets `SIGNING_*` déjà configurés sur ce dépôt. Ne pas recréer/réutiliser une autre clé et ne jamais afficher les valeurs. Le script propriétaire `<private folder> --version 0.1.0` peut lancer tests/lint/build signé et inspection statique sur un checkout canonique propre ; il n’a pas encore mené une livraison complète. Les workflows de tag doivent recevoir un commit `main` vert au SHA exact et les notes de release correspondantes. Télécharger le vrai APK distant et vérifier checksum/certificat/package/version/ABI avant de donner son lien à le mainteneur.

Les artefacts de coordination non secrets sont dans `<private folder>` (reçus, journaux, briefs, `owner-state.json`). La phase y est `paused_by_user_quota_handoff`.

## Toujours non vérifié sur téléphone

JNI réel, qualité FR/EN, interaction Gboard et insertion multi-apps, maintien du FGS/micro, mode avion puis retour de connectivité, durée réelle 15 min/2 h, mémoire, latence, batterie et chauffe. Aucun test hôte ne doit être présenté comme cette recette Pixel.
