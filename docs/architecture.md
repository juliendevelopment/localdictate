# Architecture de la dictée

## Chaîne raccordée

`MainActivity` (Compose, configuration volontaire) → `DictationController` (état dans l’Application courante, exclusion préparation/usage) → `DictationService` privé → bulle non focusable → `MicrophoneCapture` → file PCM bornée → `SileroSegmenter` → file ASR bornée → `LocalRecognizer` Parakeet → `TranscriptJournal` → revalidation de la cible et tentative d’insertion.

Le service est créé **depuis l’activité RESUMED**, après permissions microphone, notifications, overlay et activation de l’accessibilité. La promotion foreground précède tout chargement natif. `START_NOT_STICKY`, aucun receiver de boot, aucune reprise silencieuse. La bulle ne crée jamais le service. Armer charge le modèle mais ne crée pas d’AudioRecord. Une notification et la croix permettent de fermer la bulle ; l’arrêt normal coupe le micro puis termine les segments acceptés.

Les restrictions de lancement d’un FGS et l’accès microphone « while-in-use » sont distincts : [documentation Android](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). Overlay/accessibilité ne sont pas utilisés comme preuve d’autorisation de créer un FGS microphone en arrière-plan. Le maintien de l’accès micro dans le service préalablement armé, après changement d’app/verrouillage et selon version Android, reste à éprouver sur Pixel.

## Audio et calcul

- PCM16 mono 16 kHz, micro interne si disponible ; lectures partielles conservées, conversion par 32768. Aucun fichier audio.
- `AudioRecord` seulement après un toucher explicite de la bulle. `stop()` arrête et libère le recorder sous verrou indépendamment d’une file aval occupée. Une erreur de lecture ou un micro rendu muet interrompt visiblement la session.
- Deux consommateurs distincts : PCM (64 blocs, au plus environ 4,1 s) puis ASR (8 segments). Une saturation arrête le micro, garde également le bloc/segment qui a rencontré la file pleine, puis vide les données acceptées. Elle est affichée et interdit le collage automatique d’un texte potentiellement incomplet.
- Silero : fenêtres de 512, seuil 0,5, silence 400 ms, parole minimale 250 ms, longueur maximale 8 s, CPU 1 thread. La fin normale complète la dernière fenêtre, flush le VAD et borne la sortie aux vrais échantillons capturés. Une pause segmente mais ne termine jamais la session.
- Parakeet : `OfflineRecognizer`, `nemo_transducer`, features 128, CPU 2 threads, greedy search. Un seul consommateur sérialise le moteur ; chaque stream est libéré en `finally`. `OfflineRecognizerResult` est un objet JVM sans méthode native de libération, vérifié avec `javap` sur le vrai AAR.
- Limite de 14 400 000 échantillons (900 s) : coupe la dernière lecture à la limite avant arrêt du micro puis vidage. L’horloge audio n’avance pas selon la lenteur des workers ; les échantillons silencieux comptent. Une absence de données pendant 5 s est une panne, pas une détection de silence.
- Verrou CPU partiel uniquement pendant capture/traitement, libéré à l’arrêt/destruction ; timeout de sécurité 30 min. Aucun verrou CPU pendant le simple armement.

La RAM ne conserve pas les 15 min de PCM : files, un segment en cours de VAD et un décodage sont bornés. Le modèle et les allocations internes ONNX restent lourds ; seule une mesure sur Pixel peut établir leur pic réel et leur vitesse. Aucun NPU promis.

## Préparation et réseau

`ModelCatalog`, `ModelStorage`, `ResumableDownloader`, `SafeModelExtractor`, `ModelManager` sont adaptés de composants lus dans un projet voisin, sans traduction/TTS ni identité de cette application. HTTPS, tailles et SHA-256 fixés, reprise `.part`, vérification stricte de Content-Range/Length/Encoding, annulation qui déconnecte les lectures bloquantes, extraction tar+bzip2 allowlistée avec quotas, refus des liens/traversals/doublons, installation atomique et marqueur versionné. Un marqueur seul ne suffit pas : tailles/fichiers ordinaires sans symlinks sont vérifiés au lancement. Les SHA des fichiers installés sont contrôlés à l’installation ; le démarrage rapide ne rehash pas 640 MiB.

Environ 488 Mo téléchargés, 640 MiB installés ; minimum 1,5 Go libres avant préparation. Aucun poids dans Git/APK. Seule l’action Préparer appelle le gestionnaire réseau. Une exclusion commune garde la préparation interdite pendant armement, chargement, enregistrement, vidage, insertion et fermeture des handles natifs. Annuler conserve cette exclusion jusqu’à la fin effective de l’IO. Effacer est également sérialisé avec le démarrage d’une session.

AAR sherpa static-link 2.0.1 : 38 577 050 octets, SHA-256 `07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575`, tag `af2d8d81dbfa61c4cea4c568da78f3982edca005`. `fetchSherpa`/`builtBy` relie le fetch épinglé à Gradle. ABI arm64-v8a uniquement, autres chemins exclus avant merge ; aucune règle pickFirst. R8 conserve explicitement les classes JNI. Aucun binding ONNX Java ni moteur cloud.

## Cible et insertion

Le service d’accessibilité n’interroge aucun contenu dans les événements. Sur START explicite seulement, il demande le focus d’entrée de la fenêtre active, sans parcourir l’arbre ; il conserve le handle et l’identité/hachage du seul champ visé. Il refuse notre propre app, champs masqués ou de type password, non éditables, invisibles, désactivés, sans sélection valide ou sans ACTION_PASTE. Les événements sélection/texte/focus et changement de fenêtre invalident conservativement la cible jusqu’à la fin de la dictée.

À l’arrêt : rafraîchir le handle, retrouver le même focus, vérifier app/fenêtre/identité/classe/contenu/sélection/capacités. Ne jamais ACTION_SET_TEXT. Une cible manquante/modifiée ou un service révoqué donne un repli explicite. Aucun contenu de cible n’est journalisé ou persisté.

Le collage utilise un clip marqué sensible, avec jeton aléatoire, seulement après validation. L’écriture dans le presse-papiers remplace son contenu précédent : c’est annoncé dans l’interface. Le code exige de pouvoir relire notre jeton et son texte avant ACTION_PASTE ; sinon il annule. Après dispatch, il observe le texte attendu pendant au plus 1 s : une action acceptée seule ne prouve pas l’insertion. Aucun second essai automatique. Le nettoyage ne touche le presse-papiers que si jeton **et** texte correspondent encore ; aucune restauration différée d’un ancien clip.

**Limitation de plateforme connue** : [Android 10+ limite la lecture du presse-papiers aux apps ayant le focus ou à l’IME](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data). L’app conserve volontairement le focus dans l’autre application. Dans ce cas, la preuve de propriété peut être refusée et le collage automatique est annulé ; ouvrir LocalDictate puis Copier est le chemin utilisable. Cette restriction n’est pas contournée par une fenêtre focusable, un clavier substitué ou une permission privilégiée. Le fonctionnement exact et les champs compatibles doivent être vérifiés sur Pixel ; le MVP ne promet pas un collage universel.

## Récupération et confidentialité

Chaque segment reconnu est enregistré atomiquement et synchronisé sur disque dans le stockage privé. Aucun stockage partagé, sauvegarde Android/transfert désactivés, aucun log texte/audio ni analytics. Le journal conserve une seule dictée, jusqu’à Effacer. Une nouvelle dictée ne remplace jamais du texte récupérable. Le marqueur d’insertion est écrit **avant** le dispatch : après crash/recréation, aucune insertion n’est relancée. Un crash peut laisser l’état « tentative faite » sans insertion effective ; l’utilisateur vérifie puis copie explicitement.

La mort du processus perd les segments audio encore en RAM ; le texte déjà synchronisé est récupéré et présenté comme partiel. Il n’y a pas de reprise audio. Un échec disque laisse le dernier journal atomique lisible et arrête visiblement la dictée. Les tests JVM couvrent ces politiques sans charger le moteur natif Android.

## Réunion

Mode distinct, visible et **indisponible** : aucune capture 2 h, aucun résumé simulé, aucun collage. Le futur pipeline devra stocker progressivement, conserver la transcription complète et exécuter un moteur de résumé local séparé. Il reste hors de cette implémentation.
