# LocalDictate — règles du projet

Lire `docs/mvp.md`, `docs/architecture.md`, `docs/ci.md`, `docs/status.md` avant toute implémentation. Le besoin est une dictée personnelle locale FR/EN et un second mode réunion ; ce n’est pas la continuation de un projet voisin.

## Invariants

- Audio, transcriptions et résumés restent sur le téléphone. Aucun cloud de reconnaissance/résumé, analytics, crash reporting distant ni télémétrie différée. Ne pas utiliser le SpeechRecognizer système sans preuve d’un mode strictement local et sans repli réseau.
- Réseau réservé à une préparation explicite des modèles. Aucun poids dans Git/APK ; vérifier licences, tailles et SHA-256 des vrais artefacts avant intégration.
- Dictée : jusqu’à 15 min, sans arrêt sur simple silence ; insertion au curseur après arrêt. Réunion : jusqu’à 2 h, stockage incrémental, transcription complète et résumé distinct ; jamais de collage automatique depuis ce mode.
- L’utilisateur autorise overlay et accessibilité, pas une surveillance permanente. Collecter uniquement la cible d’insertion nécessaire ; jamais lire tous les écrans ou journaliser le contenu. Ne pas écrire dans les champs de mot de passe.
- Aucun texte existant ne peut être écrasé. Une cible disparue ou changée après la dictée doit déclencher une confirmation ou un repli explicite, pas un collage aveugle.
- Pas de faux moteur, transcription inventée ou bouton de démarrage qui prétend fonctionner. Distinguer fonctionnalités livrées, préparées et vérifiées sur téléphone.
- Pas de modèles/SDK sous licence incompatible ou non commerciale sans arbitrage. Pas d’achat/service payant. Pas de publication publique ni licence ajoutée sans demande.
- Secrets et clé dédiée hors checkout, dans `<private key folder>` si une release est demandée. Ne jamais réutiliser la clé d’une autre application.

## Conventions

- Code anglais ; docs et interface française. Kotlin/Compose dans `android/`, docs du code dans `docs/`, workflows à la racine.
- JDK 17, wrapper et dépendances épinglés. Utiliser le wrapper, pas le Gradle global.
- Petits commits `type: description` en français. Pousser uniquement le contenu de ce projet ; ne pas modifier un projet voisin/un autre projet.
- Après changement : tests JVM, vraie activité sous Robolectric, lint et APK ; CI verte pour le SHA exact. Un test hôte n’établit ni le fonctionnement JNI, ni l’insertion multi-app, ni l’endurance sur Pixel.
- Les autres phases ne sont pas livrées par un simple squelette vert. Mettre `docs/status.md` à jour avec la prochaine action précise et les vérifications qui restent.
