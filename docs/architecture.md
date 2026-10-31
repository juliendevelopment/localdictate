# Architecture de la dictée

## Chaîne raccordée

`MainActivity` (Compose, configuration volontaire) → `DictationController` (état dans l’Application courante, exclusion préparation/usage) → `DictationService` privé → bulle non focusable → `MicrophoneCapture` → file PCM bornée → `SileroSegmenter` → file ASR bornée → `LocalRecognizer` Parakeet → `TranscriptJournal` → revalidation de la cible et tentative d’insertion.

Le service est créé **depuis l’activité RESUMED**, après permissions microphone, notifications, overlay et activation de l’accessibilité. La promotion foreground précède tout chargement natif. `START_NOT_STICKY`, aucun receiver de boot, aucune reprise silencieuse. La bulle ne crée jamais le service. Armer charge le modèle mais ne crée pas d’AudioRecord. Une notification et la croix permettent de fermer la bulle ; l’arrêt normal coupe le micro puis termine les segments acceptés.

Les restrictions de lancement d’un FGS et l’accès microphone « while-in-use » sont distincts : [documentation Android](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). Overlay/accessibilité ne sont pas utilisés comme preuve d’autorisation de créer un FGS microphone en arrière-plan. Le maintien de l’accès micro dans le service préalablement armé, après changement d’app/verrouillage et selon version Android, reste à éprouver sur Pixel.

## Audio et calcul

- PCM16 mono 16 kHz, micro interne si disponible ; lectures partielles conservées, conversion par 32768. Aucun fichier audio.
- `AudioRecord` seulement après un toucher explicite de la bulle. Sur API 30+, son Builder appelle `setPrivacySensitive(true)` : VOICE_RECOGNITION n’est pas sensible par défaut. `stop()` arrête et libère le recorder sous verrou indépendamment d’une file aval occupée. Une erreur de lecture ou un micro rendu muet interrompt visiblement la session.
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

## Cible et insertion directe (API 33+)

Règles détaillées, journal des essais Pixel et raisons des choix : **[insertion.md](insertion.md)**. Depuis v0.1.8 :

Sur START explicite seulement, `AccessibilityService.findFocus(FOCUS_INPUT)` retrouve la saisie parmi les fenêtres interactives (`flagRetrieveInteractiveWindows`), même si la fenêtre active est la bulle. Aucun parcours de fenêtre/arbre ni lecture de contenu dans les événements, qui ne sont plus utilisés. Le snapshot retient seulement le champ choisi : handle, app, fenêtre, vue, classe. Les champs password (y compris type visible/web/numérique), notre app et les champs de plus de 32 768 caractères sont refusés. ACTION_PASTE n’est pas une condition de compatibilité.

`DirectEditorSession` n’expose aucun type de plateforme récent à la classe commune. `Api33DirectEditor` appelle les vraies méthodes publiques `getInputMethod`, `getCurrentInputConnection`, `getCurrentInputEditorInfo`, `getCurrentInputStarted` et **commitText(texte, 1, null)**. Le flag `flagInputMethodEditor` donne un éditeur limité à l’accessibilité, sans sélectionner un autre clavier, sans IME personnalisé dans les paramètres de Gboard. API 29 reste le minimum : un composant commun est activé sur API 29–32 (repli copie), un composant dédié API 33 est activé à partir de 33 par des ressources versionnées. Seul ce dernier expose les types InputMethod récents. R8 conserve les classes API 33 isolées. La vérification physique sur Android 29 reste à faire.

À l’arrêt, tout est relu : même handle au focus, dans la même app et fenêtre, modifiable et non password ; éditeur acquis **à ce moment** (une saisie redémarrée sur le même champ est normale), correspondant au champ (app, classe+variation du type, id de vue résolu) ; une seule lecture hors du thread principal de `getSurroundingText(32768,32768,GET_TEXT_WITH_STYLES)` qui doit concorder avec le nœud (texte, longueur, sélection, décalage 0). Une sélection non vide est refusée : rien n’est remplacé.

**Composition Gboard** : commitText remplace la région en composition. `GuardedInputMethod` reste passif (aucune écriture, aucune fin de composition, aucun déplacement de curseur) et mémorise sélection et bornes de composition rapportées par `onUpdateSelection`. Composition connue contenant le curseur : le mot composé est renvoyé avec la dictée à l’intérieur (`DirectContextPolicy.plan`), aucun caractère n’est perdu. Composition loin du curseur ou marqueur de composition sans position : refus. Aucun rapport depuis le début de la saisie : aucune composition posée depuis, insertion. Juste avant l’envoi unique, le champ est revalidé et l’éditeur ne doit pas avoir rapporté de changement depuis la lecture.

Après commit, relire au plus 6 × 250 ms le champ au focus (même app et id de vue) : texte exactement attendu et curseur attendu → confirmé. Sinon « demandé mais non confirmé » ; aucune réparation/répétition. La tentative est persistée dans le journal avant l’appel, et une garde empêche deux appels concurrents.

Repli : la dictée est ajoutée à l’historique privé des 5 derniers textes **avant** toute tentative. Insertion non confirmée ou refusée : copie automatique par `PrivateClipboard` (clip marqué IS_SENSITIVE, qui remplace le précédent : choix explicite de le mainteneur, 31/10/2026), bulle immédiatement prête. Le drapeau sensible masque des aperçus mais n’est pas un contrôle d’accès. Aucune lecture, suppression ou restauration de clip. Le chemin automatique n’utilise ni ACTION_PASTE ni ACTION_SET_TEXT.

Références primaires : [InputMethod](https://developer.android.com/reference/android/accessibilityservice/InputMethod), [AccessibilityInputConnection](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection), [flagInputMethodEditor](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_INPUT_METHOD_EDITOR), [AOSP InputMethod API 35](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/accessibilityservice/InputMethod.java), [copie des spans dans SurroundingText](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/inputmethod/SurroundingText.java). Signatures comparées au vrai android.jar installé (SDK 35) ; les tests de wrappers/shadows restent des fixtures, pas une preuve téléphone.

## Récupération et confidentialité

Chaque segment reconnu est enregistré atomiquement et synchronisé sur disque dans le stockage privé. Aucun stockage partagé, sauvegarde Android/transfert désactivés, aucun log texte/audio ni analytics. Le journal conserve la dictée en cours ; à la fin d’une dictée non interrompue, il est vidé une fois le texte écrit dans l’historique (5 derniers textes, stockage privé, « Effacer l’historique »). Une nouvelle dictée ne remplace jamais du texte récupérable. Le marqueur d’insertion est écrit **avant** le dispatch : après crash/recréation, aucune insertion n’est relancée. Un crash peut laisser l’état « tentative faite » sans insertion effective ; l’utilisateur vérifie puis copie explicitement.

La mort du processus perd les segments audio encore en RAM ; le texte déjà synchronisé est récupéré et présenté comme partiel. Il n’y a pas de reprise audio. Un échec disque laisse le dernier journal atomique lisible et arrête visiblement la dictée. Les tests JVM couvrent ces politiques sans charger le moteur natif Android.

## Réunion

Mode distinct, visible et **indisponible** : aucune capture 2 h, aucun résumé simulé, aucun collage. Le futur pipeline devra stocker progressivement, conserver la transcription complète et exécuter un moteur de résumé local séparé. Il reste hors de cette implémentation.
