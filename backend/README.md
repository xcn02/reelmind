# Backend facultatif ReelMind (Render)

Créer un **Web Service** Render à partir du sous-dossier `backend`, environnement **Docker**. Pas de base de données. Définir une variable secrète `REELMIND_BACKEND_TOKEN` dans Render, puis saisir la même valeur dans les réglages ReelMind.

- `GET /health` : état
- `GET /version` : yt-dlp
- `POST /extract` : `{"url":"https://www.instagram.com/reel/ID","audio":true,"images":true}`

Le service nécessite un jeton Bearer. Il est **expérimental** : Instagram peut bloquer yt-dlp ou gallery-dl. Les fichiers sont temporaires ; pas de connexion Instagram et pas de clés Gemini/Groq côté serveur.

**Sécurité :** ne pas exposer une instance publique sans authentification / limitation de débit ; utiliser une instance privée ou ajouter un jeton d'accès côté client ET serveur. La validation d'URL limite les domaines mais ne remplace pas des protections de réseau et de trafic. Les contenus sont traités uniquement si leur accès est autorisé. Les réponses `audio_base64` peuvent être volumineuses, et Render gratuit est limité : adapter la limite de durée et la taille en production.
