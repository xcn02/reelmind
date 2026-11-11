# ReelMind — release signée stable

Remplacer les deux fichiers de même chemin dans le dépôt existant. Ne pas supprimer les autres fichiers.

## 1. Créer la clé une fois sur Windows

Dans PowerShell (keytool doit être disponible via le JDK) :

    keytool -genkeypair -v -keystore "$HOME\reelmind-release.jks" -alias reelmind -keyalg RSA -keysize 3072 -validity 10000

Choisir des mots de passe solides. Ne pas envoyer le .jks ni les mots de passe dans le dépôt ou dans un chat.

## 2. Encoder la clé pour GitHub

    [Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\reelmind-release.jks")) | Set-Clipboard

Dans le dépôt GitHub : Settings > Secrets and variables > Actions > New repository secret. Créer :

- REELMIND_KEYSTORE_B64 : valeur copiée dans le presse-papiers
- REELMIND_KEYSTORE_PASSWORD : mot de passe du fichier JKS
- REELMIND_KEY_ALIAS : reelmind
- REELMIND_KEY_PASSWORD : mot de passe de la clé (souvent identique au précédent)

## 3. Compiler

Actions > Build ReelMind signed release > Run workflow. Télécharger l'artifact ReelMind-release-signed puis app-release.apk.

## Précautions

- Sauvegarder le JKS et les mots de passe hors GitHub en deux emplacements sûrs. Ne pas les publier.
- La release signée différemment ne s'installera pas par-dessus l'APK debug actuel. Sauvegarder les fiches avant de désinstaller. Un export Markdown ne restaure pas automatiquement la base SQLite.
- Garder le même applicationId, le même certificat et augmenter versionCode pour les futures mises à jour.
- isMinifyEnabled reste false : ce changement crée une release signée, pas encore une optimisation R8.
- Ce patch ne corrige pas les éventuelles erreurs Kotlin de la V0.4.
