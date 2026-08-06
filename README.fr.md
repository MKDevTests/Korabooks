# Korabooks — votre bibliothèque Calibre-Web, hors ligne sur Android

[English](README.md) · **Français**

Korabooks recopie une bibliothèque [Calibre-Web](https://github.com/janeczku/calibre-web)
via son flux OPDS dans une base locale sur le téléphone, et la lit de là.
Parcourir, chercher, filtrer et trier dix mille livres ne touche jamais au
serveur : le catalogue est parcouru une fois, et tout le reste est une requête
SQLite. Les livres sont téléchargés à la demande.

> **Alpha.** La version 0.1.0 est une première build, partagée pour être testée.
> Attendez-vous à des aspérités, et à ce qu'une version future reconstruise la
> base locale de zéro.

## Ce qu'il fait

- **Recopie un catalogue OPDS** — une adresse, un identifiant. La synchro
  complète parcourt toute la bibliothèque ; « Nouveautés » ne lit que ce que le
  serveur a ajouté depuis la dernière fois et s'arrête dès qu'il ne trouve rien
  de neuf.
- **Livres, séries, auteurs, genres** — quatre entrées dans la même
  bibliothèque. Une bibliothèque Calibre est surtout faite de livres isolés,
  donc les livres viennent en premier ; l'onglet Genres reconstruit l'arbre que
  la notation pointée de Calibre sous-entend.
- **Lit les EPUB et les PDF** hors ligne, et retient la progression de lecture.
- **Densité d'affichage réglable**, partout où ça veut dire quelque chose.

## Ce qu'il n'est pas

Korabooks est un fork de [Kora](https://github.com/MKDevTests/Kora), lui-même un
fork de [Komelia](https://github.com/Snd-R/Komelia). Kora est un client Komga
pour les mangas ; Korabooks est le même moteur pointé sur une bibliothèque de
livres. Les fonctions propres au manga (OCR, upscaling, AniList) sont encore
dans l'arbre mais ne sont plus embarquées dans l'APK et n'ont plus d'appelant.

Il parle à Calibre-Web **uniquement en OPDS**, donc il voit exactement ce
qu'OPDS publie : titre, auteur, série, langue, tags. Les colonnes personnalisées
de Calibre n'en font pas partie — si vos genres vivent dans l'une d'elles,
publiez-les comme tags.

## Installer

Récupérez l'APK dans les [Releases](https://github.com/MKDevTests/Korabooks/releases),
autorisez l'installation depuis une source inconnue, et ouvrez-le. Android 8 ou
plus récent, arm64 uniquement.

Ensuite : **Paramètres → Catalogue**, entrez l'adresse du flux OPDS de votre
Calibre-Web (`http://192.168.1.10:8083/opds`) et votre identifiant, puis
« Tout resynchroniser ». Dix mille livres prennent une vingtaine de minutes, et
la bibliothèque est consultable pendant ce temps.

## Compiler

```bash
./scripts/build-kora-debug.sh      # APK debug, installé sur l'appareil connecté
./scripts/build-kora-release.sh    # APK release signé
```

Nécessite le SDK Android et un JDK 17. `git clone --recursive`, ou
`git submodule update --init` ensuite — plusieurs dépendances sont des
sous-modules.

## Licence

Apache 2.0, héritée de Komelia. Voir [LICENSE](LICENSE).
