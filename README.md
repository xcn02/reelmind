# ReelMind Android v0.2.1 (Kotlin + Jetpack Compose)

## Nouveautés 0.2.1
- Icône adaptative bleu ardoise sans bordure blanche, marque-page + cerveau électronique.
- Splash screen sombre natif (Android 12+) et fond de lancement sombre pour Android 8–11.
- Création de Room déplacée hors du thread principal ; état de chargement affiché dans la bibliothèque.
- Initialisation du ViewModel protégée contre les appels répétés.
- versionCode 2 pour mise à jour de la V0.1, sous réserve de la même signature.

## Fonctionnalités
- Réception de liens Instagram depuis le partage Android et saisie manuelle.
- Bibliothèque SQLite Room, recherche, favoris, notes et catégories.
- Ajout manuel de texte source (légende ou transcription).
- Résumé en français par API Gemini (clé personnelle, réseau requis).
- Export Markdown d’une fiche ou de la bibliothèque par le menu de partage Android.
- Migration Room 1 → 2 : conservation des fiches de v0.1 lors de la mise à jour.

## Non inclus
- Extraction automatique des vidéos, légendes et transcriptions Instagram.
- Connexion effective aux fournisseurs Groq, OpenAI ou Ollama (seul Gemini est actif).
- Chat avec les Reels, transcription audio, notifications, synchronisation Notion.

## Installer
Dans GitHub Actions, lancer `Build ReelMind APK`, puis télécharger l'artifact `ReelMind-v0.2.1-APK` et installer l'APK debug.

**Important** : installer la nouvelle version par-dessus l’ancienne (même applicationId et signature debug). Une signature différente peut nécessiter une désinstallation et ferait perdre les données locales. Sauvegarder/exporter la bibliothèque avant toute désinstallation. Les clés Gemini sont stockées dans SharedPreferences non chiffrées et envoyées uniquement à l’API Gemini ; ne pas utiliser de clé sensible sur un appareil non fiable.

## Fonctionnement
`MainActivity` reçoit le partage et affiche l'UI Compose ; `ReelModel` (ViewModel) gère l'état, les coroutines et les opérations Room ; `ReelDatabase` persiste les fiches ; `GeminiClient` envoie le texte source à l'API et reçoit un résumé. L’UI observe les changements automatiquement.
