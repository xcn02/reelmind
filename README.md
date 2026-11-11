# ReelMind Android V0.4 — lot principal (Kotlin / Compose)

Ce projet reprend les fichiers du V0.3, avec une migration Room **3 → 4**, sans effacement volontaire des données. Le projet est une **version de test** : ni compilation Android ni validation sur téléphone n'ont été exécutées dans cet environnement.

## Fonctionnalités ajoutées dans ce lot

1. **Partage Instagram → bibliothèque** : récupère le lien et le texte supplémentaire *si Android en fournit*. Détecte les URL déjà enregistrées.
2. **Traitement asynchrone persistant** : WorkManager lance une tentative de récupération de métadonnées publiques, puis un résumé IA si une clé est configurée. Le processus ne dépend pas de l'écran ouvert.
3. **Limites d'Instagram explicites** : l'extraction se limite aux balises publiques `og:description`/`description`. Instagram bloque souvent cette lecture. Dans ce cas, l'état « Contenu inaccessible » apparaît. Aucune tentative d'authentification automatisée, de contournement ni de téléchargement clandestin.
4. **Import audio/vidéo local** : sélection d'un fichier `audio/*` ou `video/*`, transcription par Gemini 2.5 Flash pour les fichiers de **12 Mio max**. Le fichier est envoyé à Gemini, **uniquement après sélection explicite**. Pour cette fonction, choisir Gemini + saisir sa clé API.
5. **Résumé, tags et statuts** : si du texte est disponible, Gemini, Groq, OpenAI ou Ollama peuvent le résumer en français; progression dans la fiche, nouvelle tentative et état d'erreur enregistrés en SQLite.
6. **Notifications** : avertissement quand le résumé aboutit ou échoue, sous réserve d'autorisation Android. Bouton « Activer les notifications » dans Réglages.
7. **Questions sur la bibliothèque** : envoie au fournisseur IA les contenus/résumés des **25 fiches les plus récentes disposant de texte** et répond avec référence au titre/lien. Ce n'est pas une recherche vectorielle exhaustive.
8. **Toutes les fonctions V0.3 conservées** : chat par fiche, favoris, notes, recherche, catégories/tags, TTS, export Markdown, interface sombre et pictogramme bleu ardoise.

## Ce qui n'est PAS encore intégré

- Transcription automatique de la piste audio d'un Reel **à partir de son URL Instagram** : Android ne reçoit généralement pas cette piste lors du partage et les restrictions d'Instagram empêchent de garantir l'accès.
- Récupération fiable du titre, de la miniature ou de la vidéo de chaque Reel (ne pas supposer qu'une métadonnée publique est disponible).
- Synchronisation cloud, indexation vectorielle complète, import direct Notion/Obsidian via leur API, réimport de sauvegardes.
- Exécution fiable sur un réseau non disponible : WorkManager persiste la tâche mais les appels IA requièrent Internet. Les reprises après erreur ne sont pas encore sophistiquées.

## Compilation GitHub Actions

- Copier **tout** le contenu de l'archive à la racine du dépôt (`app/`, `.github/`, fichiers Gradle).
- Aller dans **Actions → Build ReelMind APK**, lancer `Run workflow`.
- Télécharger l'artefact **ReelMind-0.4-release-test**, puis son `app-release.apk`.
- `release` est un APK de **test signé par la clé debug de la machine CI**. Cette clé peut changer entre deux compilations; cela **peut empêcher de mettre à jour** la version précédente sans désinstallation, laquelle effacerait les données locales. Prévoir une signature persistante avant de stocker durablement les données. Ne pas effacer la V0.3 avant d'avoir sauvegardé.

## Tester en priorité

1. Ouvrir ReelMind et vérifier la migration sans perte de fiches.
2. Configurer une clé Gemini, partager un Reel et ouvrir sa fiche. Selon les restrictions Instagram, soit le texte public est extrait puis résumé, soit une erreur explicite s'affiche.
3. En cas d'échec, coller la légende dans **Texte source**, puis appuyer sur **Générer le résumé IA**.
4. Importer un fichier audio **court et léger** depuis la fiche. Le contenu est envoyé à Gemini; si la transcription réussit et « Traitement automatique » est activé, le résumé se lance ensuite via WorkManager.
5. Tester « Poser une question sur ma bibliothèque », les notifications, les discussions individuelles et l'export Markdown.

## Mesurer le démarrage, plutôt que le deviner

Le workflow compile une variante **release de test**, sans `debuggable`, mais sans minification R8. Avec ADB (si installé), exécuter plusieurs fois :

```
adb shell am force-stop fr.reelmind.app
adb shell am start -W -n fr.reelmind.app/.MainActivity
```

La sortie donne notamment `TotalTime`. Répéter le test à conditions comparables; la fenêtre de lancement sombre masque la blancheur mais n'est pas une accélération en soi. Si le délai reste proche de 5 secondes, il faudra une trace Android Perfetto / Macrobenchmark.

## Où regarder pour apprendre

- `MainActivity.kt` : UI Compose, partage Android, état ViewModel, Room et migrations.
- `ReelProcessing.kt` : `CoroutineWorker` WorkManager, traitement en arrière-plan, métadonnées publiques et notifications.
- `MediaTranscriber.kt` : lecture contrôlée d'un fichier Android via `ContentResolver` et appel Gemini multimodal.
- `AiProviders.kt` : appels HTTP aux modèles IA.
- `.github/workflows/android.yml` : construction automatisée de l'APK.

## Confidentialité / coûts

Les textes (et fichiers transmis pour transcription) sont envoyés au fournisseur choisi. Les clés API sont sauvegardées dans les SharedPreferences sans chiffrement applicatif dédié. Pour un premier test, utiliser une clé à faible quota et ne pas stocker de données sensibles. Les fournisseurs peuvent facturer les appels, selon leur tarification et quota propres. L'application n'intègre pas d'abonnement ReelMind.
