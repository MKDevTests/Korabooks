# Korabooks — règles de développement, pièges et historique

Fork Android/KMP de **Kora** (client Komga) transformé en client d'une
bibliothèque **Calibre-Web** lue en **OPDS**. Kora lui-même n'est jamais modifié.

Bibliothèque de référence : ~10 500 livres, serveur Calibre-Web sur NAS
(`http://192.168.1.30:8083/opds`).

---

## 1. Règles de fonctionnement (non négociables)

1. **Jamais de build debug depuis `main`.** Le développement vit sur
   `books/scaffold` ; `main` ne sert qu'aux releases. `build-kora-debug.sh`
   refuse de tourner depuis `main` — c'est voulu.
2. **C'est l'utilisateur qui lance les builds et les installations.** L'agent
   vérifie en compilant des modules, pas en installant sur le téléphone.
3. **adb depuis PowerShell uniquement.**
4. **`L:\Livres_Calibre\metadata.db` : LECTURE SEULE.** Jamais d'écriture,
   jamais de montage en écriture.
5. **Réponses en français**, style ADHD : action d'abord, étapes numérotées,
   listes plafonnées à 5, un pas concret (< 2 min) à la fin, pas de préambule
   ni de récapitulatif.
6. **Toujours fournir un plan de test avec la commande de build.**
7. **Mesurer avant d'affirmer.** Cette règle a été apprise à la dure : trois
   diagnostics de synchro successifs se sont révélés faux tant qu'on a raisonné
   au lieu de lire les logs et la base.

---

## 2. Commandes

### Build debug (utilisateur, depuis `books/scaffold`)
```bash
cd /mnt/c/Users/mathi/Downloads/Dev/Korabooks && ./scripts/build-kora-debug.sh
```

### Vérification de compilation (agent)
```bash
wsl -e bash -lc 'export ANDROID_HOME=$HOME/android-sdk; export ANDROID_SDK_ROOT=$ANDROID_HOME; cd /mnt/c/Users/mathi/Downloads/Dev/Korabooks && ./gradlew --console=plain <tâche>'
```
Tâches utiles : `:komelia-ui:compileDebugKotlinAndroid`,
`:komelia-app:compileDebugKotlinAndroid`,
`:komelia-domain:core:testDebugUnitTest`,
`:komelia-infra:database:sqlite:compileDebugKotlinAndroid`.

### Release
```bash
cd /mnt/c/Users/mathi/Downloads/Dev/Korabooks && ./scripts/release-korabooks.sh <version> <fichier-notes>
```
Doit tourner depuis `main`, arbre propre. Après la release, revenir sur
`books/scaffold` et le fast-forwarder sur `main`.

### Diagnostic sur l'appareil
```powershell
# journal applicatif
adb pull /sdcard/Android/data/io.github.mkdevtests.korabooks.debug/files/komelia/logs/komelia.log <dest>

# base du miroir (le nom du fichier est server_1_offline.sqlite, PAS offline.sqlite)
cmd /c "adb exec-out run-as io.github.mkdevtests.korabooks.debug cat files/server_1_offline.sqlite > `"<dest>`""

# piloter l'interface
adb shell "uiautomator dump /sdcard/ui.xml"; adb shell "cat /sdcard/ui.xml"
adb shell "input tap <x> <y>"; adb shell "input text 'mot'"
```

### Monter `L:` dans WSL
```bash
sudo mkdir -p /mnt/l && sudo mount -t drvfs 'L:' /mnt/l
```

---

## 3. Pièges connus

1. **PowerShell corrompt les binaires.** `adb exec-out … > fichier` injecte des
   CR. Toujours passer par `cmd /c "… > fichier"`.
2. **`git commit -m @'…'@` casse** dès que le message contient des guillemets.
   Utiliser `git commit -F <fichier>`.
3. **`gh.exe` est un binaire Windows.** Lui passer un chemin `/mnt/c/...` donne
   « fichier introuvable » — c'est ce qui a fait échouer silencieusement la
   publication de la v0.2.0 alors que le tag était déjà poussé. Le script
   traduit désormais les chemins avec `wslpath -w`.
4. **Gradle peut annoncer `UP-TO-DATE` à tort** après des écritures côté
   Windows. Vérifier avec un canari (introduire une erreur de syntaxe volontaire
   et confirmer qu'elle est détectée) avant de conclure qu'une compilation est
   bonne.
5. **`OpdsCatalogueService.kt` contient un octet NUL** (`const val SEPARATOR`) :
   grep le voit comme binaire, utiliser `grep -a`.
6. **Les APK compressent les chaînes DEX** : grep sur l'APK donne des faux
   négatifs. Dézipper `classes*.dex` avant de chercher.
7. **CRLF/LF** : `git status` peut montrer des fichiers modifiés à tort. Utiliser
   `git diff --ignore-cr-at-eol`.
8. **`cut -c1-140` tronque les logs** et fait lire `offset=6780` comme
   `offset=67`. Ne pas diagnostiquer sur des lignes tronquées.
9. **Ne jamais builder depuis Windows / Git Bash.** Deux dégâts distincts :
   le wrapper (Gradle 9.3.1) n'est pas téléchargeable côté Windows — TLS
   intercepté, `PKIX path building failed`, et seul un `.part` traîne dans le
   cache ; et le git de Windows a `core.autocrlf=true`, donc tout fichier qu'il
   touche repart en CRLF sur le disque et WSL le voit modifié à tort (§3.7).
   Le build est en WSL, point. Un chemin `/c/...` au lieu de `/mnt/c/...` est
   le premier symptôme qu'on s'est trompé de shell.
10. **`ANDROID_HOME` est exporté dans `~/.bashrc`**, que `bash -lc` ne source
    pas (non interactif). D'où l'échec « SDK location not found » quand on
    lance un build par `wsl -e bash -lc` sans l'exporter — c'est pour ça que la
    commande du §2 l'exporte explicitement. `local.properties` ne sauve pas :
    son `sdk.dir` est un chemin Windows, invalide depuis WSL.

---

## 4. Ce que la mesure a établi sur la synchro

1. **Le serveur répond à une requête à la fois**, une toutes les ~3,3 s, quel
   que soit le parallélisme envoyé. Donc **temps total = nombre de requêtes**.
   Aucune optimisation client ne change ça.
2. **Phase Livres** : ~53 pages à 200 livres/page. Le réglage
   « Livres par page » de Calibre-Web (Administration → Configuration de
   l'interface, max 200) est le plus gros levier existant.
3. **Phase Séries** : une requête par série, ~2 780 → plusieurs heures.
   Incompressible en OPDS : le protocole n'a pas de champ série, et Calibre-Web
   ne publie aucun compteur permettant de savoir laquelle a changé (vérifié).
   D'où le bouton **« Reprendre »**.
4. **« Reprendre »** saute les séries déjà regroupées. Aucun point de reprise
   n'est stocké : le miroir est sa propre trace, une étagère portant 2 livres ou
   plus n'a pu le devenir qu'en étant regroupée.
5. **Les lettres de l'index ne sont pas une partition.** Lire les lettres au
   lieu de l'entrée « Tout » a fait perdre 2 055 livres sur 10 561. On lit
   « Tout », on ignore les lettres.
6. **Les échecs de requête ne doivent jamais être avalés** : un peek perdu
   emporte tout son sous-arbre et la synchro annonce une bibliothèque plus
   petite comme si c'était la réponse.

---

## 5. Ce que la mesure a établi sur les genres

> **⚠️ La source a été nettoyée depuis (mesuré le 2026-08-07).** Les points 2 à 4
> ci-dessous décrivent un état révolu. `L:` est `\\192.168.1.30\Lectures`, donc
> `/mnt/l/Livres_Calibre/metadata.db` **est** le fichier que Calibre-Web sert.
> Ce qu'il contient aujourd'hui, sur 10 542 livres :
> - **214 tags**, pas 1 191. Tous hiérarchisés et propres, aucun orphelin,
>   32 seulement sur un livre unique. Top 5 : `Policier/thriller` (4 429),
>   `SF` (2 192), `Non_Fiction` (1 583), `Fantasy` (1 466), `Suspense` (1 167).
> - Les trois scories citées au point 4 (`1001Ebooks.com`, `1715-1789`,
>   `1914-1918 -- Campaigns -- France`) **n'existent plus** dans `tags`.
> - `custom_columns` ne contient plus que `id=1 mots`. La table
>   `custom_column_14` survit avec ses 214 valeurs, **identiques à `tags`** :
>   la colonne `genre` a été versée dans les tags puis supprimée.
>
> Conséquence : les 1 191 genres encore visibles dans l'application sont des
> lignes de miroir **périmées**, écrites par une synchro antérieure au nettoyage.
> Elles ne sont pas orphelines (leurs séries existent), donc la jointure de
> `3367b62d` ne les élimine pas — seule une synchro qui réécrit les genres de
> chaque série le fera, c'est-à-dire **« Tout resynchroniser »**, pas
> « Nouveautés » ni « Reprendre ». Soit des heures (§4.3).
> **Miroir de l'appareil mesuré** (`server_1_offline.sqlite`, tiré le
> 2026-08-07, `flyway = ['1']`, 7 138 séries, 8 001 lignes de genre) :
> - **1 191 genres distincts, tous rattachés à une série vivante.** La jointure
>   de `3367b62d` n'en élimine aucun : ce ne sont pas des orphelins.
> - Intersection avec les 214 du serveur : **139**. Donc **1 052 périmés**
>   (`&#160;`, `+ 13 ans`, `1001Ebooks.com`, `1715-1789`…) et surtout
>   **75 des 214 vrais genres absents du miroir** (`Anime`, `Classique`,
>   `Fantasy.Dark_Fantasy`…).
>
> **Conclusion : « Tout resynchroniser » est obligatoire, la liste blanche ne
> peut pas s'y substituer.** Cocher ne choisit que parmi ce que le miroir
> connaît, et 75 genres réels n'y sont pas ; coller les 214 n'en cocherait
> que 139. La re-synchro fait bien le ménage :
> `ExposedOfflineSeriesMetadataRepository:57` supprime les genres d'une série
> avant de les réécrire.
>
> La liste blanche garde son intérêt **après** : 214 genres restent longs, et
> ils sont hiérarchisés sur **58 racines**. Le bonus `SF` → `SF.*` de §7.1,
> noté « pas fait », devient de loin la partie la plus utile.

1. Les genres viennent de `<category>` dans le flux OPDS = champ **tags** de
   Calibre (`OpdsMapping.kt`).
2. **Le flux ne porte aucune provenance** : l'attribut `scheme` est toujours le
   même (BISAC). Impossible de distinguer un genre défini par l'utilisateur
   d'une scorie héritée.
3. **La forme ne trie pas** : 766 des 1 191 genres sont des mots simples,
   légitimes et scories mélangés.
4. **Aucun bug côté Korabooks** : le serveur publie réellement ces 1 191
   catégories (`1001Ebooks.com`, `1715-1789`, `1914-1918 -- Campaigns -- France`
   sont bien dans `/opds/category/letter/1`).
5. **La solution retenue** : liste blanche gérée par l'app. L'utilisateur a déjà
   une colonne Calibre `genre` (`custom_columns` id 14, texte, multivaluée)
   contenant **214 genres hiérarchisés propres**. Extraction :
   ```bash
   cd /mnt/l/Livres_Calibre && sqlite3 metadata.db "select value from custom_column_14 order by value;"
   ```
6. `metadata.db` reste un **amorçage optionnel côté PC**, jamais une dépendance
   de l'application : un client OPDS qui exige un accès fichier au serveur n'est
   plus un client OPDS.

---

## 6. Historique des décisions

- **v0.1.0** puis **v0.2.0** publiées sur `MKDevTests/Korabooks` (dépôt public).
  L'updater pointe sur ce dépôt — pointer sur Kora remplacerait Korabooks par un
  client manga chez les utilisateurs.
- **Ménage manga-only**, en tranches qui compilent chacune :
  1. 9 entrées manga retirées du menu Réglages (constante `MANGA_TOOLS_VISIBLE`
     pour le lecteur, retrait du menu pour Komf).
  2. 5 modules `komelia-komf-extension` supprimés (37 fichiers, jamais dans
     l'APK).
  3. Interface Komf supprimée : 36 fichiers, 9 fabriques de ViewModel, 4 menus
     contextuels.
  4. Bibliothèque `komf-client` sortie du build.
- **Gardés volontairement** : `WEB_KOMF` (valeur de `PlatformType`, tissée dans
  les deux lecteurs et le login), `KomfSettingsRepository` (branché au service de
  sauvegarde — le retirer changerait le format des sauvegardes),
  `V10__komf_settings.sql` (une migration est de l'historique, pas du code).
- **Non fait volontairement** : ncnn/ONNX et AniList. Ce ne sont pas des blocs
  détachables — les états ncnn/ONNX sont construits par `ReaderViewModel` et
  traversent 5 fichiers du lecteur ; AniList occupe la moitié de
  `SeriesLinksState` (753 lignes). Les couper, c'est opérer dans des
  fonctionnalités qui marchent.
- **Bloc Komf du proto de réglages** : à ne pas toucher. Un champ inutilisé ne
  coûte rien ; une migration ratée coûte les réglages des utilisateurs.

---

## 7. Reste à faire

1. **Liste blanche de genres — livrée** (`69cd6d4f` sur `books/scaffold`,
   2026-08-07, 14 fichiers, +453 lignes). Ce qui est en place :
   - `migrations/offline/V2__retained_genres.sql`, enregistrée dans
     `OfflineMigrations.kt`. Table `RETAINED_GENRE (genre TEXT PRIMARY KEY)`,
     vide par défaut.
   - `findAllGenres` et `findAllGenresByLibraries` filtrent dessus. Liste vide
     = `null` = aucun filtre, pour ne rien cacher aux installations existantes.
     (`findAllGenresByCollection` renvoie déjà `emptyList()` en amont.)
   - `RetainedGenreRepository` (domaine) + `ExposedRetainedGenreRepository` :
     liste, comptes par genre (`count(distinct series)`, joint sur les séries
     vivantes, tri décroissant), remplacement complet.
   - Écran `Réglages → Genres` : cases à cocher `genre · N`, champ « coller une
     liste », champ de recherche, « Tout décocher », « Enregistrer ».
   - Câblage réel : `OfflineRepositories` (`OfflineModule.kt`),
     `AndroidAppModule`, `DesktopAppModule` (en base **inscriptible**, ses
     voisins y sont en lecture seule), `ViewModelFactory`,
     `SettingsNavigationMenu`. **Pas** `CoreModule` — la spécification se
     trompait de fichier.
   - **Pas fait** : le bonus hiérarchique (cocher `SF` proposant `SF.*`).
   - **Non vérifié** : jamais installée ni exécutée. Compilé seulement, et le
     seul « BUILD SUCCESSFUL » obtenu vient d'un Gradle Windows — sans valeur
     tant qu'un canari (§3.4) ne l'a pas confirmé sous WSL.
   - **Tranchée par la mesure (§5)** : la liste blanche ne remplace pas la
     re-synchro complète, elle vient après. Ordre : installer cette version,
     lancer « Tout resynchroniser », puis raccourcir les 214 genres obtenus.
2. ~~**Bonus hiérarchique `SF` → `SF.*`**~~ — **livré** (2026-08-07). L'écran
   groupe par racine (`genreRoot`, découpe sur le point), case **tri-état** par
   famille, familles repliées par défaut et dépliées d'office quand une
   recherche est active. Le compte d'une famille est un **distinct** de séries,
   pas la somme de ses enfants — sommer gonflait chaque famille dès qu'une série
   portait `Fantasy` *et* `Fantasy.Historique`. Ajouté aussi :
   « Cocher les N affichés » / « Décocher les affichés », qui suivent le filtre
   de recherche.
3. **Bug latent `ExposedOfflineSeriesMetadataRepository:71-72`** : la garde
   teste `metadata.tags.isNotEmpty()` mais la boucle itère `metadata.genres`.
   Les tags de série reçoivent donc les genres, et les vrais tags sont perdus.
   Invisible ici — la table des tags du miroir est **vide** (0 ligne), ce flux
   OPDS ne remplit que les genres. À corriger avant toute source qui fournit
   les deux.
4. Collections manuelles.
5. KOSync.
6. Recherche plein texte dans le livre.
