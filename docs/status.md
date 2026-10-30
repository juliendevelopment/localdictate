# État du projet — 30 octobre 2026

## Dictée réelle raccordée, recette Pixel à faire

La lane `work/dictation`, base `65467b15994f49e1467267e35bc612209cc4b6bb`, contient les modifications **non commitées** pour revue par l’agent. Aucun push, tag, publication, workflow modifié, secret ou clé de signature créé. L’APK debug utilise la clé de développement déjà présente ; la release locale reste non signée. Aucune CI d’un nouveau SHA n’est prétendue verte.

Livré dans le code : préparation volontaire Parakeet/Silero avec reprise, annulation, tailles/SHA-256, extraction bornée et installation atomique ; service microphone privé armé depuis activité visible ; notification et bulle déplaçable non focusable ; capture 16 kHz, Silero et vrai OfflineRecognizer Parakeet ; deux files bornées, arrêt à 900 s capturées puis vidage ; erreurs/surcharge visibles ; transcription privée synchronisée progressivement ; revalidation ciblée, ACTION_PASTE sous conditions, preuve d’insertion observée ou statut incertain, copie/effacement explicites et récupération sans doublon après interruption.

Le mode réunion reste visible et **indisponible**. Aucun résumé ni modèle de traduction/reformulation dans cette phase.

## Vérification locale finale

Commande réellement exécutée depuis la racine du worktree (JDK 17.0.20, SDK existant, un worker, aucun job modèle parallèle) :

```sh
ANDROID_HOME=/path/to/android-sdk \
PATH=$JAVA_HOME/bin:$PATH \
./android/gradlew -p android --no-daemon --max-workers=1 --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  -ProbolectricDependencyDir=/path/to/robolectric-cache \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
```

**BUILD SUCCESSFUL**, 5 min 4 s, 103 tâches (24 exécutées, 79 à jour). Le premier essai Robolectric échouait avant les tests Android car son verrou HOME était interdit par le sandbox ; l’option locale ci-dessus utilise les vrais SDK jars déjà en cache en mode offline, sans configuration globale ni test supprimé. Les passes antérieures ont aussi exercé intégralement les builds.

**76 tests JUnit, 0 échec, 0 erreur, 0 ignoré**, comptés dans les XML de la dernière passe :

| Classe | Tests |
| --- | ---: |
| MainActivityTest | 8 |
| asr.DictationPipelineTest | 7 |
| audio.PcmFramerTest | 3 |
| insertion.InsertionServiceTest | 10 |
| insertion.PrivateClipboardTest | 1 |
| insertion.TargetPolicyTest | 5 |
| models.ResumableDownloaderTest | 15 |
| models.SafeModelExtractorTest | 9 |
| session.DictationServiceTest | 3 |
| session.SessionBudgetTest | 8 |
| session.TranscriptJournalTest | 7 |

Les 8 tests de `MainActivityTest` lancent la vraie activité Compose/Robolectric avec son Application actuelle : aucune écoute/préparation au lancement ou au retour de permission, liens et disclosure, limites des affordances, réunion honnête, recréation/récupération. Les tests d’insertion utilisent des nœuds/actions Android de fixture, y compris refus de lecture du clip ; ils ne prouvent pas l’accès multi-app réel du téléphone. Les tests de préparation utilisent de petits octets/archives déterministes et une vraie lecture bloquée déconnectée à l’annulation, sans télécharger de poids.

Lint : **0 erreur, 16 avertissements non masqués** (versions/target plus récents disponibles, ABI ChromeOS absente par choix arm64, espace utilisable, suggestions KTX, listener tactile qui appelle explicitement performClick). Les versions imposées n’ont pas été mises à niveau. `assembleRelease` a exécuté R8 ; classes JNI sherpa conservées. AAR réel de 38 577 050 octets rehashé et API javap vérifiée. Aucun test hôte ne charge le runtime Android avec les modèles.

Rapports : `android/app/build/test-results/testDebugUnitTest/`, `android/app/build/reports/tests/testDebugUnitTest/index.html`, `android/app/build/reports/lint-results-debug.html` et XML associé. La commande finale a été capturée dans `/tmp/localdictate-validation-verified.log`.

## APK réellement produits

| Chemin depuis la racine | Octets | SHA-256 |
| --- | ---: | --- |
| `android/app/build/outputs/apk/debug/app-debug.apk` | 50 066 517 | `a601cabff2710d9d42523dc387814e99d3a9af8cd684807000ddd2407f09ad0a` |
| `android/app/build/outputs/apk/release/app-release-unsigned.apk` | 26 587 863 | `d9e28a1e31c44679f59dad243b2a4842341b1bc9ac46e1ee052a05395ff8d092` |

`android/app/build/outputs/apk/SHA256SUMS` contient aussi les empreintes. Archives inspectées : seuls `lib/arm64-v8a/libsherpa-onnx-jni.so` et `lib/arm64-v8a/libandroidx.graphics.path.so`, aucune autre ABI ni poids ASR/VAD. `DebugProbesKt.bin` est une ressource de coroutines, pas un modèle. Notices tierces embarquées. Manifest : Internet réservé au code de préparation, microphone/FGS microphone, overlay, notifications et verrou CPU ; sauvegarde désactivée, accessibilité protégée par BIND_ACCESSIBILITY_SERVICE, service microphone non exporté. Aucun SpeechRecognizer/ML Kit ni appel réseau hors des composants de préparation n’est présent dans le code applicatif.

## Limite connue et prochaine action précise

**Le collage automatique n’est pas garanti et peut être annulé systématiquement sur Android standard sans focus** : Android 10+ peut refuser la lecture du presse-papiers à notre app en arrière-plan. Le code exige cette preuve de propriété pour éviter un collage non maîtrisé. Il conserve alors la transcription et affiche le repli : ouvrir LocalDictate, Copier (clip sensible remplaçant l’ancien contenu), coller soi-même. Aucun champ changé/password n’est forcé et aucune action dispatchée seule n’est annoncée comme une insertion observée. Voir [architecture](architecture.md) et [restriction Android](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data). Il s’agit d’une limite de plateforme connue, pas d’un test téléphone réussi. Aucun blocage de dépendance/build ne reste.

**Prochaine action l’agent** : revue de ce diff isolé, décision sur le repli de collage, commit/CI pour le SHA exact, signature dédiée puis installation de la release minifiée sur Pixel. Commencer par préparation, armement visible sans AudioRecord, une dictée FR puis EN et le cas de presse-papiers inaccessible, selon [la recette Pixel](acceptation-pixel.md). Le code reste ici sans commit pour cette revue.

## Vérifications téléphone non faites

Chargement JNI et vraie reconnaissance avec les poids, qualité FR/EN, mémoire/latence, maintien du microphone FGS après changement d’app/verrouillage, arrêt matériel effectif, notifications/permissions révoquées, conservation du focus et insertion dans des apps natives/web, récupération après mort réelle du processus, endurance 15 min, trafic en mode avion puis au retour réseau, batterie/chaleur. La mort du processus perd le son encore en RAM ; seul le texte déjà synchronisé est récupérable. Réunion 2 h et résumé restent non implémentés. Aucune signature/livraison stable ou mise à jour sur téléphone n’est validée ici.
