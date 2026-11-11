# ReelMind Android — V0.1 (Kotlin + Jetpack Compose)

Projet Android natif, sans Flutter. Prototype de bibliothèque locale avec Room/SQLite, recherche, favoris, fiches éditables, réception de liens Instagram via Android ACTION_SEND, paramètres de fournisseur IA.

## Fonctionnalités non disponibles dans cette version
Pas encore d'extraction automatique de légende/vidéo, transcription, génération de résumé par LLM, chat, notifications ni synchronisation. Le champ « résumé » est éditable manuellement. Les paramètres IA sont sauvegardés mais ne déclenchent aucun appel réseau.

## Compiler sans PC avec GitHub
1. Créer un dépôt GitHub privé vide.
2. Importer **le contenu** de cette archive à la racine du dépôt (pas un dossier parent). Le fichier `.github/workflows/android.yml` doit être présent.
3. Ouvrir **Actions → Build ReelMind APK → Run workflow**, ou attendre la compilation déclenchée par le push sur main.
4. Ouvrir l'exécution terminée, télécharger l'artefact `ReelMind-v0.1-APK`, puis installer `app-debug.apk` sur Android.

Si GitHub n'affiche que des modèles de workflow, le dossier caché `.github/workflows` n'a pas été importé. Créer `.github/workflows/android.yml` manuellement avec le contenu du fichier de cette archive.

## Configuration
- Android min SDK 26, target SDK 35
- JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21
- Compilation avec `gradle :app:assembleDebug` (ou Gradle Wrapper si ajouté localement)

## Confidentialité
Les fiches sont conservées dans une base Room locale. Aucune clé API n'est demandée ni enregistrée dans cette version. Ne jamais publier de clés API dans GitHub.

## État de validation
Le projet a été généré, mais la compilation Gradle n'a pas pu être exécutée dans l'environnement de création faute de SDK Android et de Gradle. Le premier run GitHub Actions permettra de confirmer le build et d'identifier toute erreur éventuelle.
