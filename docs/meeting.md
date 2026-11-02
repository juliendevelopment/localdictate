# Noyau réunion — résumé extractif local

## Livré, et non livré

Ce lot fournit un **noyau Kotlin/JVM réellement exécuté et testé**, sans Android, coroutines, moteur natif, dépendance applicative supplémentaire, réseau, modèle ou télémétrie :

- stockage incrémental PCM16 little-endian, mono, 16 kHz ; plafond exact de 2 h ; export WAV en flux ; reprise explicite d’un fichier interrompu ;
- journal durable de vrais textes **fournis par l’appelant**, avec identifiants et intervalles audio, vérification CRC/UTF-8 et récupération contrôlée du préfixe valide ;
- **résumé extractif local** FR/EN : sélection de passages originaux, répartis dans le temps, avec liens vers leurs segments et offsets exacts.

Ce n’est **ni une capture microphone livrée, ni un moteur ASR, ni un résumé génératif équivalent à un LLM**. Le noyau n’invente aucune transcription et ne sait pas reconnaître la parole. Il ne génère ni décisions ni tâches et ne classe pas les phrases dans ces catégories. Il conserve la langue, la ponctuation et les négations des extraits choisis, sans garantir que les extraits seuls suffisent à restituer tout leur contexte.

Restent à intégrer ailleurs : capture `AudioRecord`, file bornée et service foreground, permissions/notification/arrêt, véritable ASR FR/EN, gestion des interruptions Android et de la reprise après reboot, UI de réunion, consultation de la transcription complète, persistance/affichage du résumé et export/suppression volontaires. **Aucune endurance de 2 h, mesure RAM/thermique/latence, qualité ASR/résumé ou validation de confidentialité sur Pixel n’est établie par ces tests hôte.** Ce lot ne modifie ni Gradle, ni le Manifest, ni l’UI, ni les services.

## Fichiers

Package `be.arte.localdictate.meeting` :

- [`MeetingStorage.kt`](../android/app/src/main/java/be/arte/localdictate/meeting/MeetingStorage.kt) : garde d’espace injectable et ouverture exclusive sans remplacement ;
- [`PcmRecording.kt`](../android/app/src/main/java/be/arte/localdictate/meeting/PcmRecording.kt) : enregistrement PCM et finalisation WAV ;
- [`TranscriptJournal.kt`](../android/app/src/main/java/be/arte/localdictate/meeting/TranscriptJournal.kt) : segments, journal, lecteur en flux, diagnostic/récupération ;
- [`LocalExtractiveSummarizer.kt`](../android/app/src/main/java/be/arte/localdictate/meeting/LocalExtractiveSummarizer.kt) : extraction FR/EN, bornes et traçabilité ;
- tests : `android/app/src/test/java/be/arte/localdictate/meeting/{PcmRecordingTest,TranscriptJournalTest,LocalExtractiveSummarizerTest,MeetingStorageTest}.kt`.

## Contrat de stockage et d’exécution

L’appelant fournit des `File` **absolus, dans le stockage app-private**, avec un parent déjà créé. La preuve qu’un parent appartient bien à `filesDir`/`noBackupFilesDir`, sa politique de sauvegarde, les droits et le consentement sont la responsabilité de l’intégration Android : du Kotlin/JVM pur ne peut pas les déduire. Les chemins ne sont jamais construits à partir de mots, de titres ou d’une sortie ASR. Le composant final d’un chemin symbolique est refusé (`NOFOLLOW_LINKS`) ; les parents fournis restent une frontière de confiance, pas un sandbox anti-administrateur.

Toutes les créations utilisent `CREATE_NEW` : un fichier, répertoire ou lien préexistant n’est **jamais remplacé**. La récupération est une opération séparée et explicite sur un fichier existant ; elle n’en crée pas un manquant. Verrous exclusifs pour les écrivains/reprises, partagé pour le lecteur ; une ouverture concurrente incompatible échoue immédiatement. Ces verrous protègent les utilisateurs coopératifs de cette API, pas un autre programme ignorant les verrous. Les objets sont synchrones et destinés à un propriétaire unique : les appeler sur une tâche d’E/S sérialisée, jamais sur le thread UI ni directement sur un callback microphone qui ne doit pas attendre un `force` disque. Aucun thread ou accumulateur caché n’est créé.

```kotlin
val storage: StorageGuard = ReservedSpaceGuard() // marge par défaut : 16 Mio
// Variante : StorageGuard { file, additionalBytes -> ... }, qui lève IOException si refus.
```

La garde intervient avant création, avant chaque append et avant l’allocation d’un WAV complet. Elle est injectable, ne constitue **pas une réservation disque** et ne dispense pas de traiter les vraies erreurs d’écriture/synchronisation. `IOException` n’entraîne aucune suppression automatique. Les tailles persistées et compteurs cumulés sont des `Long` ; les tailles de petits buffers/offsets de chaînes restent des `Int`.

Chaque append audio et chaque segment acquitté passent par `FileChannel.force(true)`. Une erreur d’E/S pendant l’écriture/synchronisation rend l’écrivain inutilisable : le fermer puis récupérer explicitement. Un refus préalable de la garde n’écrit rien et autorise une nouvelle tentative. Après une écriture ayant échoué, un dernier enregistrement peut malgré tout être complet sur disque ; sa présence après vérification ne prouve pas qu’il avait été acquitté. La synchronisation du fichier n’est pas une preuve d’atomicité de tout le système de fichiers, du répertoire parent ou d’un matériel défaillant.

## Audio : API et format

```kotlin
val recording = PcmRecording.create(pcmFile, storage)
val accepted = recording.append(buffer, byteOffset, byteLength)
// accepted.acceptedBytes / rejectedBytes / limitReached : arrêt explicite du producteur à la limite.
val samples: Long = recording.sampleCount
val bytes: Long = recording.pcmBytes
val durationMs: Long = recording.durationMs
val wav: WavRecording = recording.finishWav(newWavFile)
recording.close()
```

- Format unique : 16 000 échantillons/s, un canal, 16 bits signés little-endian. Paramètres incompatibles refusés, pas de rééchantillonnage implicite.
- Limite par défaut : **115 200 000 échantillons, 230 400 000 octets PCM, 7 200 000 ms**. `maxDurationMs` peut réduire cette limite, jamais dépasser 2 h ; il doit être compris entre 1 ms et 2 h.
- Append de 0 à **65 536 octets**, longueur paire et plage valide. Aucun buffer n’est conservé après le retour. Un buffer chevauchant la limite est accepté uniquement jusqu’au dernier échantillon autorisé ; les octets refusés et l’état de limite sont explicitement retournés. L’intégration doit arrêter la capture, afficher la limite et traiter ce résultat, pas ignorer les octets refusés.
- Le spool est un conteneur privé, **pas un WAV brut** : 32 octets d’en-tête `LDPCM001`, paramètres et limite en little-endian, puis les échantillons. Pour un consommateur PCM explicite, l’audio commence à l’offset `PcmRecording.HEADER_BYTES`. Le noyau ne charge ni ne segmente l’audio pour un ASR.
- `finishWav` crée un nouveau WAV RIFF/PCM canonique de 44 octets d’en-tête et copie l’audio avec un buffer fixe de **16 384 octets**. Après succès, ce handle n’accepte plus d’audio. `close()` seul ferme et conserve le spool ; il ne l’exporte ni ne le supprime.
- Le WAV est une **copie**, pas un renommage : à 2 h, spool et WAV nécessitent **460 800 076 octets** ensemble (environ 439,45 Mio), auxquels ajouter journal, marge et autres fichiers. Une destination d’export interrompue peut rester incomplète ; elle n’est jamais remplacée lors d’un nouvel essai. Ne présenter comme finalisé que le `WavRecording` effectivement retourné ; réessayer vers un nouveau `File`. Le spool reste intact.

Reprise :

```kotlin
val recovery = PcmRecording.recover(existingPcmFile, storage)
val lostTailBytes: Long = recovery.discardedIncompleteBytes
val resumed = recovery.recording
// Continuer avec append(), ou exporter directement ; fermer ensuite.
```

La reprise vérifie l’en-tête et la taille sans lire tout l’audio. Au plus un octet final de demi-échantillon peut être retiré, et ce retrait est rapporté. En-tête tronqué/incompatible ou fichier au-delà de sa limite : refus, sans réduction arbitraire à 2 h. **Le PCM n’a pas de checksum par buffer** : la reprise restaure la structure et le nombre d’échantillons disponibles, pas une certification des sons, d’octets non synchronisés ou d’une corruption matérielle. Après un arrêt de capture, l’intégration doit conserver la limite totale et les timestamps audio ; elle ne doit pas réinterpréter une reprise comme une nouvelle session de 2 h.

## Journal de transcription : API et récupération

```kotlin
TranscriptJournal.create(journalFile, storage).use { journal ->
    // Ces valeurs doivent venir du vrai moteur ASR et de l’horloge de l’audio.
    val stored: TranscriptSegment = journal.append(startMs, endMs, actualAsrText)
}
TranscriptJournal.openReader(journalFile).use { reader ->
    val report = reader.scan { segment -> consumeOneSegment(segment) }
    report.requireComplete() // sinon JournalTailException, avec diagnostic et octets non vérifiés
}
```

Les segments ont des IDs séquentiels `Long` à partir de 1, des intervalles `0 <= startMs < endMs <= 7 200 000`, ordonnés et sans chevauchement. L’intégration ASR doit gérer ses fenêtres de recouvrement avant append : le journal ne supprime ni ne devine des mots. Texte non blanc, au plus **16 384 unités UTF-16 / 65 536 octets UTF-8**, UTF-8 strict (pas de remplacement silencieux d’un surrogate invalide). Plafond explicite de **100 000 segments** ; le dépassement lève une erreur et n’est jamais traité comme une transcription complète tronquée.

Le fichier commence par `LDTXT001`. Chaque trame comporte une longueur 32 bits, un payload borné (`id`, `startMs`, `endMs` en 64 bits, longueur UTF-8 32 bits, texte), puis CRC32 du payload. Les nombres du journal sont big-endian. CRC, ID, ordre, intervalle, longueur et encodage sont validés **avant** d’exposer un segment au consommateur. CRC32 détecte des erreurs accidentelles, ce n’est pas une authentification contre un adversaire.

Le lecteur conserve un seul enregistrement borné ; un appelant qui stocke toutes les valeurs des callbacks crée lui-même un accumulateur. `scan()` ne modifie rien et s’arrête au premier enregistrement non vérifiable. Il retourne le nombre et les octets du préfixe vérifié, `tailIssue` et `tailBytes`. Il n’essaie pas de « retrouver » des phrases dans des octets corrompus. Une exception du consommateur est propagée et ne déclenche jamais une réparation.

```kotlin
// Contrôle sans autoriser la destruction d’un suffixe non vérifié :
val healthy = TranscriptJournal.recover(journalFile, storage) // lève si queue endommagée
healthy.journal.close()

// Après décision explicite de l’intégration/utilisateur sur le diagnostic :
val recovery = TranscriptJournal.recover(journalFile, storage, repairTail = true)
val reportBeforeRepair = recovery.scanBeforeRepair
recovery.journal.use { journal -> /* append de nouveaux vrais segments, si nécessaire */ }
```

`repairTail = true` retire **tout le suffixe non vérifié à partir de la première erreur**, puis synchronise le préfixe et reprend les IDs. C’est précisément le cas d’une dernière trame tronquée/corrompue ; si la corruption est au milieu, les octets ultérieurs restent non fiables et ne sont pas récupérés automatiquement, même si certains ressemblent à des trames. Présenter le diagnostic et préserver une copie privée avant réparation si une analyse plus poussée est nécessaire. L’en-tête incomplet n’est jamais remplacé par un faux journal vide.

## Résumé extractif local : API, couverture et bornes

```kotlin
// Fermer l’écrivain avant d’ouvrir le lecteur partagé du résumé.
val summary = LocalExtractiveSummarizer().summarize(
    journalFile,
    SummaryLanguage.FRENCH, // ou ENGLISH ; aucune traduction
    ExtractiveSummaryOptions(),
)
```

Le moteur ouvre le journal en **lecture seule** et maintient le verrou sur deux passages complets (un seul suffit pour constater un journal vide). Premier passage : métadonnées et fréquences lexicales dans un tableau fixe de 4 096 cases de hachage. Deuxième : frontières de phrases `BreakIterator` FR/EN, classement lexical approché et réservoirs bornés par tranche temporelle. Les collisions de hachage influencent seulement le classement, jamais le texte. Aucune fenêtre de contexte et aucun arrêt de lecture après avoir rempli le résumé : tous les segments des 2 h sont examinés, sinon une erreur est propagée. Les résultats sont déterministes pour un même contenu, réglage et environnement JVM ; les règles linguistiques de `BreakIterator` peuvent varier entre runtimes.

Par défaut : au plus **12 extraits**, **6 000 caractères sélectionnés**, **1 000 caractères par phrase**, **4 parties temporelles** réparties de 0 jusqu’à la fin du dernier segment. Les très courtes réunions réduisent le nombre de parties pour éviter des intervalles vides. Une meilleure phrase entière est réservée à chaque partie possédant une phrase admissible, puis les places restantes sont complétées par classement, dans les budgets. Le résultat final est remis dans l’ordre source. Des phrases identiques peuvent représenter deux parties différentes ; les compléments ne répètent pas un texte déjà retenu.

Les réglages refusent les demandes impossibles/non bornées : 1–64 extraits, 1–65 536 caractères au total, 1–4 096 par phrase, 1–16 parties et pas plus de parties que d’extraits. Le budget doit permettre une phrase maximale par partie. Le moteur garde au plus `coverageParts * maxSentences` candidats, un segment courant et son tableau de fréquences, **indépendamment de la longueur totale du journal**.

Les phrases trop longues ne sont **ni coupées ni réécrites** ; `oversizedSentences` les compte. `sentencesExamined`, `eligibleSentences`, `omittedEligibleSentences`, `source.segmentCount`, `source.textCharacters`, `source.lastEndMs`, `coverage` et `uncoveredPartIndexes` rendent les omissions et zones sans extrait visibles. L’UI future doit exposer ces limites et donner accès au journal complet, pas faire passer une partie sans extrait pour une partie absente de la transcription.

Chaque `SourceSentence` fournit :

- `text`, **sous-chaîne exacte** de son segment (seuls les espaces extérieurs de la phrase sont exclus) ;
- `segmentId`, `sourceAnchor = "segment-<id>"` ; l’UI doit fournir la cible de ce lien dans la transcription, pas en faire un chemin disque ;
- `startCharacter`, `endCharacterExclusive`, offsets UTF-16 dans le segment original ;
- `segmentStartMs` et `segmentEndMs`, repris exactement du segment, **pas des timestamps de mots/phrases estimés**.

Le journal vide donne des listes de phrases et de couverture vides, sans inventer d’intervalle de temps ni affirmer « aucune décision ». Un suffixe corrompu fait échouer le résumé avec son diagnostic : il ne répare, n’écrase et ne supprime **jamais** le journal ou l’audio. L’absence de persistance automatique du résumé permet à l’intégration de traiter son échec indépendamment de la conservation de la transcription.

## Vérifications réellement exécutées

Worktree : `/home/fedora/agent-managed/projects/localdictate/meeting-worktree`.

Pilote hôte éphémère, créé dans le scratch autorisé, utilisant uniquement les JAR déjà en cache :

```sh
python /home/fedora/.hermes/cache/scratch/localdictate-meeting-validation/run_tests.py
```

Ce pilote a effectivement exécuté **Kotlin 2.1.0 `org.jetbrains.kotlin.cli.jvm.K2JVMCompiler`**, cible JVM 17, avec `-Xmx256m`, puis **JUnit 4.13.2 `org.junit.runner.JUnitCore`** avec `-Xmx64m`, processus sérialisés, `-XX:+UseSerialGC`, et `java.io.tmpdir`/`TMPDIR` exclusivement dans le scratch. Les JAR coroutines éventuellement nécessaires au compilateur font partie de son classpath d’outil, **pas** du classpath applicatif/test runtime de ce noyau.

Dernière commande de validation, réellement exécutée avec log complet des deux commandes Java :

```sh
python /home/fedora/.hermes/cache/scratch/localdictate-meeting-validation/run_tests.py \
  > /home/fedora/.hermes/cache/scratch/localdictate-meeting-validation/final-tests.log 2>&1
```

Résultat : **`OK (36 tests)`**, aucun échec, exécution JUnit **1,957 s**. Des cycles rouge/vert ont reproduit et corrigé deux défauts d’arrondi des parties temporelles puis un intervalle de couverture artificiel sur journal vide ; les tests correspondants sont conservés. Les tests couvrent :

- en-tête WAV/endianness/données et enregistrement vide ; limite exacte et buffer à cheval ; reprise d’un demi-échantillon ; formats/tailles invalides ; stockage refusé et erreur E/S réelle à l’ouverture d’une destination ; absence de remplacement ;
- export en flux d’un PCM synthétique **230 400 000 octets** sous heap JVM **64 Mio**, depuis un spool creux, et réutilisation de petits buffers sans conservation ; cela teste la taille et la mémoire hôte, **pas deux heures de capture réelle** ;
- Unicode, négations et texte de traversal traité comme texte ; troncature à chaque position de la dernière trame ; mauvais CRC, longueur hostile, mauvais UTF-8 malgré CRC valide, ordre invalide ; reprise, verrous, erreurs consommateur et fichiers manquants ;
- FR/EN, vide, phrases exactes/offsets/horodatages/liens, classement non limité à l’ouverture, budgets, exclusions explicites, couverture de plusieurs parties, journal complet synthétique de 2 h, petits intervalles/arrondis et conservation intégrale du journal en cas d’échec du résumé ;
- garde d’espace sans overflow, liens symboliques finaux et chemins non absolus refusés.

Tous les textes et sons de tests sont explicitement **synthétiques**, jamais présentés comme des résultats de transcription utilisateur. Les appels `readBytes()` des tests ne lisent que de petites fixtures ; le code de production n’utilise aucun chargement intégral de fichier.

**Non exécutés dans ce lot :** Gradle, Robolectric, lint Android, build/APK, moteur natif, capture/ASR, campagne de coupures matérielles et essais Pixel. Le parent doit intégrer puis lancer la vraie suite Gradle et les validations appareil ; les 36 tests JVM ne les remplacent pas. Aucun commit, push, tag ou publication n’a été effectué par ce lot.
