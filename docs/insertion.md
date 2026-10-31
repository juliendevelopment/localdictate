# Insertion au curseur — conception, essais Pixel et diagnostic

Ce document est la référence sur l’insertion automatique de la dictée. Il consigne les essais faits par le mainteneur sur son Pixel les 30 et 31 octobre 2026 (v0.1.0 à v0.1.7), l’analyse de leurs échecs et les règles adoptées en v0.1.8. **Le lire avant de toucher à `insertion/` ou à la fin de session de `DictationService`.**

## Flux attendu par le mainteneur

1. Armer la bulle une fois depuis l’app (« Armer la bulle — micro fermé »).
2. Dans n’importe quelle app : placer le curseur, toucher **● Dicter**, parler (jusqu’à 15 min, souvent 5 à 10 min), toucher **■ Arrêter**.
3. Le texte apparaît au curseur ; la bulle revient sur **● Dicter** pour la dictée suivante, sans passer par l’app.
4. En cas de problème : le texte est dans le presse-papiers (appui long → Coller) et dans l’app (5 derniers textes). **Ne jamais perdre une dictée** : redire 10 minutes n’est pas acceptable.

## Journal des essais sur le Pixel (Google Keep, Gboard)

| Version | Constat sur le téléphone | Message de l’app | Cause | Changement |
|---|---|---|---|---|
| 0.1.0 | Play Protect bloque l’installation, puis l’accessibilité est refusée | « App blocked… », « App was denied access » | Applis hors Play Store demandant l’accessibilité | Désactiver l’analyse Play Protect ; « Autoriser les paramètres restreints » (voir plus bas) |
| 0.1.0 | Bulle introuvable | — | La bulle n’apparaît qu’après « Armer la bulle » | Expliqué ; pas de changement de code |
| 0.1.0 | Jamais inséré dans Keep | « accessibilité/cible indisponible » | Aucune raison affichée | 0.1.1 affiche la raison |
| 0.1.1 | Idem | « éditeur différent du champ (type champ=704513 éditeur=2801665) » | EditorInfo ajoute un drapeau (0x200000) au type de saisie | 0.1.2 compare classe + variation seulement |
| 0.1.2 | **Inséré**, mais bulle bloquée sur « Ouvrir le texte » | « Insertion demandée mais non confirmée » | Relecture unique à 350 ms invalidée par les événements de Keep/Gboard | 0.1.3 relit 6 × 250 ms ; insertion confirmée → bulle libérée |
| 0.1.3 | Non inséré (note déjà remplie) | « Cible ou éditeur absent, modifié ou incompatible » | Plusieurs refus possibles derrière un seul message | 0.1.4 détaille chaque refus ; Copier ne masque plus la raison |
| 0.1.4 | Inséré, non confirmé | « (fenêtre ou focus modifié) » | Événement de focus juste après l’envoi | 0.1.5 confirme par relecture du champ |
| 0.1.5 | Non inséré (dictée de 10 s) | « invalidée : événement 4194304 de null » | `TYPE_WINDOWS_CHANGED` (clavier, bulle) | 0.1.6 ignore cet événement ; copie automatique ; historique de 5 textes |
| 0.1.6 | 1re dictée insérée, 2e non | « invalidée : événement 32 de com.android.systemui » | Fenêtre de l’interface système (notification de premier plan mise à jour par la dictée elle-même) | 0.1.7 ignore les événements des autres applis |
| 0.1.7 | Plusieurs dictées, aucune insérée ; copie et historique OK | « Éditeur non observable, texte en composition ou contexte incompatible » | Voir l’analyse : composition « inconnue » refusée | 0.1.8 : refonte décrite ci-dessous |

Ce qui a fonctionné sur le téléphone : installation par-dessus avec la même clé, préparation Parakeet + Silero, reconnaissance française correcte (ponctuation, majuscules), bulle déplaçable, insertion réelle dans Keep (0.1.2, 0.1.4, 0.1.6 première dictée), copie automatique et historique (0.1.6, 0.1.7).

## Pourquoi on tournait en rond

La conception initiale exigeait que **le monde reste figé** entre ● Dicter et ■ Arrêter, c’est-à-dire pendant des secondes à des minutes : même nœud et même session d’éditeur (même génération), aucun événement de fenêtre/focus/texte, même texte et même sélection, et un état de composition déjà rapporté par le clavier. Sur un vrai téléphone ces signaux bougent sans danger pendant une dictée : le clavier et la bulle changent de fenêtre, la notification de premier plan est mise à jour à chaque phase, Keep et Gboard émettent focus/fenêtre, l’éditeur peut redémarrer la saisie. Chaque version a levé une de ces conditions et révélé la suivante : ce sont des symptômes d’une même erreur de conception, pas des bugs indépendants.

Le dernier refus (0.1.7) l’illustre. L’éditeur d’accessibilité n’apprend la sélection et la composition que par `onUpdateSelection`, appelé **seulement quand elles changent**. Après un simple toucher dans le champ, rien ne change ensuite : l’état restait « inconnu », traité comme une composition possible, donc refusé. La première dictée passait parfois parce qu’un mouvement de curseur avait eu lieu juste avant.

## Règles depuis v0.1.8

Une seule décision, prise à l’arrêt, sur des lectures fraîches, limitée à ce qui protège réellement le texte (`InsertionService.insert`, `DirectContextPolicy.plan`) :

1. **Au démarrage**, on retient seulement *quel champ* l’utilisateur a choisi : nœud, app, fenêtre, identifiant de vue, classe. Champ mot de passe, notre app, champ non modifiable ou de plus de 32 768 caractères : pas de cible (dictée quand même, repli à l’arrêt).
2. **Les événements d’accessibilité ne décident plus rien** (`onAccessibilityEvent` est vide).
3. **À l’arrêt** : le champ au focus doit être le même nœud, dans la même app et la même fenêtre, modifiable, pas un mot de passe. Le texte peut avoir changé (insertion au curseur actuel).
4. L’éditeur est acquis **à ce moment-là** (une saisie redémarrée sur le même champ est normale) et doit correspondre au champ (app, classe+variation du type, id de vue).
5. Une lecture `getSurroundingText` complète : texte, longueur et sélection identiques à ceux du nœud, décalage 0.
6. **Sélection non vide → refus** : rien n’est jamais remplacé.
7. **Composition Gboard** : `commitText` remplace la région en composition. Si sa position est connue (`onUpdateSelection`) et qu’elle contient le curseur, on renvoie le mot composé avec la dictée à l’intérieur : tous les caractères existants restent. Composition loin du curseur, ou marqueur de composition sans position : refus. Aucun rapport depuis le début de la saisie = aucune composition posée depuis : on insère.
8. Revalidation du champ juste avant l’envoi, **un seul envoi** (tentative persistée dans le journal avant), puis relecture : même champ avec exactement le texte attendu → confirmé.
9. **Fin de dictée** (`DictationService`) : le texte va dans l’historique **avant** toute tentative. Confirmé → journal vidé. Sinon → copie automatique dans le presse-papiers (remplace le clip précédent, accepté explicitement par le mainteneur), journal vidé si l’historique est écrit, bulle prête avec la mention « Non inséré • texte copié, collez-le ». Dictée interrompue : journal conservé comme avant.

Ces règles remplacent celles d’`architecture.md` antérieures à v0.1.8 (composition inconnue refusée, invalidation par événements, session d’éditeur figée, remplacement de la sélection).

## Risques restants, à vérifier sur le Pixel

- Hypothèse de la règle 7 : une composition présente *avant* le démarrage de la saisie et jamais rapportée ensuite. Les claviers terminent leur composition en fin de saisie ; si un mot venait à disparaître après une insertion, c’est ce cas. La relecture le signalerait alors comme « non confirmée », avec le texte copié.
- Champs web (navigateur), applis à éditeur personnalisé, RecyclerView qui recrée le champ (nœud différent → repli copie). Seul Google Keep a été essayé.
- Dictées longues (5–10 min) : non essayées sur le téléphone ; mémoire, chauffe, batterie inconnues.
- Android 29–32 : pas d’insertion directe (repli copie), non vérifié physiquement.

## Lire un message de refus

| Début du message | Sens |
|---|---|
| « Pas de champ compatible au démarrage (…) » | Au toucher de ● Dicter, pas de champ utilisable (raison entre parenthèses) |
| « Insertion refusée sans rien modifier dans le champ. (…) » | À l’arrêt : autre champ/app, texte sélectionné, éditeur absent, composition hors curseur… |
| « Éditeur non observable ou contexte incompatible : insertion annulée (… ; éditeur : …) » | Le champ et l’éditeur ne concordent pas ; `éditeur :` donne connu/sélection/composition |
| « Insertion demandée mais non confirmée (…) » | Envoi fait, relecture différente : vérifier le champ avant de coller (risque de doublon) |

Aucun message ne contient de texte du champ : seulement des longueurs, positions et noms de paquet.

## Installation sur le Pixel (rappel)

- Télécharger l’APK de la release GitHub `android-vX.Y.Z` (pas `app-debug.apk`).
- Play Protect bloque l’APK (« App blocked to protect your device », sans bouton d’installation) : Play Store → avatar → Play Protect → roue dentée → désactiver l’analyse le temps d’installer, puis réactiver.
- Accessibilité refusée (« App was denied access ») : Infos de l’appli → menu ⋮ → **Autoriser les paramètres restreints**, puis réactiver le service. La permission de bulle (overlay) peut demander la même chose.
- Chaque nouvelle version s’installe par-dessus et conserve les modèles.
