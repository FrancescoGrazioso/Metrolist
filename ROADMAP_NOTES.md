# Metrolist — Note di sviluppo e roadmap

> Documento interno non committabile. Basato sul feedback dei primi ~1000 utenti da GitHub.
> Ultimo aggiornamento: 27 febbraio 2026

---

## Indice

1. [Sincronizzazione like bidirezionale con Spotify](#1-sincronizzazione-like-bidirezionale-con-spotify)
2. [Sincronizzazione artisti seguiti e feed](#2-sincronizzazione-artisti-seguiti-e-feed)
3. [Miglioramento sezione "Nuove Uscite" — integrazione ibrida](#3-miglioramento-sezione-nuove-uscite--integrazione-ibrida)
4. [Android Auto — playlist Spotify non visibili](#4-android-auto--playlist-spotify-non-visibili)
5. [Regressione integrazione Last.fm](#5-regressione-integrazione-lastfm)
6. [Analisi buffer della coda e comportamento offline](#6-analisi-buffer-della-coda-e-comportamento-offline)
7. [Errore riproduzione: "video unavailable, code io_unspecified 2000"](#7-errore-riproduzione-video-unavailable-code-io_unspecified-2000)
8. [Rischio ban account — valutazione e comunicazione](#8-rischio-ban-account--valutazione-e-comunicazione)
9. [Supporto file audio locali](#9-supporto-file-audio-locali)
10. [Sezioni Home biased verso liked songs recenti](#10-sezioni-home-biased-verso-liked-songs-recenti)
11. [Endpoint GQL alternativi per aggirare rate limiting REST](#11-endpoint-gql-alternativi-per-aggirare-il-rate-limiting-rest-di-spotify)

---

## 1. Sincronizzazione like bidirezionale con Spotify

### Segnalazione
Quando un utente mette "Mi piace" a una canzone dentro Metrolist, vorrebbe che questa venga aggiunta anche ai "Liked Songs" del proprio account Spotify.

### Stato attuale
L'integrazione Spotify è attualmente **unidirezionale (lettura)**. L'app recupera i liked songs da Spotify tramite GraphQL (`queryLikedSongs`) e li mappa su YouTube Music per la riproduzione. Il flusso è:
- `SpotifyLikedSongsViewModel` carica i liked songs paginati (50 per pagina)
- `SpotifyYouTubeMapper` esegue fuzzy matching (titolo/artista/durata) per trovare l'equivalente su YouTube
- I match vengono cachati in `SpotifyMatchEntity` nel database Room

Non esiste al momento alcun meccanismo di **scrittura** verso le API Spotify.

### Analisi tecnica
- **API necessaria:** `PUT https://api.spotify.com/v1/me/tracks?ids={ids}` (REST) oppure la mutation GraphQL equivalente `addToLibrary`
- **Autenticazione:** L'app utilizza già token di sessione Spotify tramite cookie WebView e TOTP. Occorre verificare che lo scope dei permessi includa `user-library-modify`. Se il login è basato su cookie di sessione (non OAuth standard), potrebbe essere necessario testare se le API di scrittura funzionano con quel tipo di autenticazione.
- **Complessità del matching inverso:** Quando l'utente mette like a una traccia che proviene da YouTube (non da una playlist Spotify), serve un **reverse matching** YouTube → Spotify. Questo è più complesso perché:
  - Bisogna cercare la traccia su Spotify per titolo/artista
  - Il matching potrebbe non essere univoco
  - Tracce non presenti su Spotify non possono essere sincronizzate
- **Conflitti di sincronizzazione:** Se l'utente toglie un like su Metrolist, lo togliamo anche da Spotify? Serve definire la politica di sync (mirror bidirezionale vs. solo aggiunta).

### Priorità suggerita: **Media**
### Complessità stimata: **Media-Alta**

### File coinvolti
- `spotify/src/main/kotlin/com/metrolist/spotify/Spotify.kt` — aggiungere metodo `addToLikedSongs()`
- `app/src/main/kotlin/com/metrolist/music/viewmodels/SpotifyLikedSongsViewModel.kt` — logica di sync inverso
- `app/src/main/kotlin/com/metrolist/music/playback/SpotifyYouTubeMapper.kt` — reverse matching
- `app/src/main/kotlin/com/metrolist/music/db/` — eventuale tabella di coda per sync pendenti (offline)

### Rischi e considerazioni
- Il reverse matching potrebbe produrre falsi positivi (traccia YouTube sbagliata associata a traccia Spotify diversa)
- Sincronizzare automaticamente potrebbe generare "rumore" nei liked songs Spotify dell'utente
- Sarebbe saggio offrire l'opzione come opt-in nelle impostazioni

---

## 2. Sincronizzazione artisti seguiti e feed

### Segnalazione
Gli utenti vorrebbero poter sincronizzare gli artisti che seguono su Spotify e recuperare i relativi feed (ultime uscite, concerti, ecc.).

### Stato attuale
L'app recupera già alcune informazioni sugli artisti tramite le API Spotify GraphQL, ma non c'è un meccanismo dedicato per:
- Importare la lista completa degli artisti seguiti
- Creare un feed personalizzato basato sugli artisti seguiti
- Sincronizzare il follow/unfollow di artisti tra Metrolist e Spotify

### Analisi tecnica
- **API per artisti seguiti:** `GET https://api.spotify.com/v1/me/following?type=artist` (REST) oppure query GraphQL equivalente
- **Feed artisti:** Una volta ottenuta la lista, si possono usare endpoint come `queryWhatsNewFeed` (già usato per le nuove uscite) filtrando per artisti seguiti
- **Database locale:** Serve una tabella o estensione dell'entità `ArtistEntity` per tracciare lo stato di follow e la fonte (Spotify, YouTube, locale)
- **Matching artisti:** Il matching artista Spotify → YouTube è meno problematico del matching tracce (nomi artisti sono più univoci), ma ci sono eccezioni (artisti con nomi comuni, alias, featuring)

### Priorità suggerita: **Media**
### Complessità stimata: **Media**

### Possibile implementazione
1. Aggiungere endpoint `getFollowedArtists()` in `Spotify.kt`
2. Creare `SpotifyArtistSyncViewModel` per gestire la sincronizzazione
3. Estendere `ArtistEntity` con campo `spotifyId` e `followedOnSpotify`
4. Nel feed home, aggregare uscite dagli artisti seguiti con priorità
5. Prevedere un meccanismo di refresh periodico (WorkManager)

---

## 3. Miglioramento sezione "Nuove Uscite" — integrazione ibrida

### Segnalazione
Utenti si lamentano della sezione "Nuove Uscite". Si propone un approccio ibrido: usare gli artisti seguiti su Spotify ma sfruttare il catalogo release di YouTube Music per la riproduzione.

### Stato attuale
La sezione "Nuove Uscite" funziona così:
- **Fonte dati:** query GraphQL Spotify `queryWhatsNewFeed` (hash SHA256 persistito)
- **Ordinamento:** per affinità artisti (preferiti → più ascoltati → altri)
- **Filtro:** supporto contenuti espliciti tramite `filterExplicit()`
- **Posizione:** sezione "spotify_new_releases" nella Home quando "Use Spotify for Home" è attivo

**File chiave:**
- `NewReleaseViewModel.kt` — ViewModel che carica e ordina le release
- `NewReleaseScreen.kt` — UI della schermata
- `Spotify.kt` (linee ~795-821) — metodo `newReleases()`
- `HomeViewModel.kt` (linee ~668-678) — integrazione nella Home

### Problemi noti
- Il feed `queryWhatsNewFeed` di Spotify potrebbe non essere completo o non riflettere le preferenze dell'utente se l'account Spotify non è molto attivo
- La dipendenza totale da Spotify per le nuove uscite è un singolo punto di fallimento
- Alcuni artisti pubblicano su YouTube prima che su Spotify (o viceversa)

### Stato dei fix — COMPLETATO

Implementato nel branch `development`. La sezione "Nuove Uscite" è ora completamente personalizzata con tre tab filtrabili e integrazione ibrida Spotify + YouTube.

**Architettura implementata:**

1. **Artisti seguiti da Spotify (GQL `libraryV3`):**
   - Nuovo endpoint `Spotify.myArtists()` che recupera tutti gli artisti seguiti dall'utente tramite GQL (zero rate limit)
   - Cache persistente in `SpotifyProfileCache` con TTL 12h e fallback su DataStore

2. **Related Artists (GQL `queryArtistOverview`):**
   - Nuovo endpoint `Spotify.artistRelatedArtists()` che estrae gli artisti correlati dal campo `relatedContent.relatedArtists` della stessa query GQL già usata per le top tracks (zero rate limit)
   - Cache in `SpotifyProfileCache` con TTL 24h: per i top 10 artisti seguiti, raccoglie i nomi normalizzati dei related artists
   - Persistenza su DataStore per disponibilità immediata all'avvio

3. **Tab "Following" — Nuove uscite da artisti seguiti:**
   - Feed personalizzato Spotify (`queryWhatsNewFeed`) filtrato per artisti presenti nella libreria dell'utente
   - Include anche artisti bookmarked nel DB locale di Metrolist

4. **Tab "Discover" — Nuove uscite da artisti correlati (YouTube):**
   - Fetcha `YouTube.newReleaseAlbums()` (catalogo globale YouTube Music)
   - Filtra i risultati mantenendo **solo** album il cui artista corrisponde (fuzzy match normalizzato) a un related artist dei seguiti
   - Esclude artisti già seguiti per evitare duplicati con la tab Following
   - Deduplica per titolo album rispetto alla tab Following
   - Esempio: se l'utente segue Bring Me The Horizon, in Discover appariranno nuove uscite di Bad Omens, Architects, ecc.

5. **Tab "For You" — Vista combinata:**
   - Mostra Following + Discover insieme per una panoramica completa

6. **UI con ChipsRow:**
   - Tre chip selezionabili: "For You", "Following (N)", "Discover (N)" con conteggi dinamici
   - Shimmer loading durante il caricamento iniziale

**File modificati:**
- `spotify/src/main/kotlin/com/metrolist/spotify/Spotify.kt` — `myArtists()`, `artistRelatedArtists()`
- `app/src/main/kotlin/com/metrolist/music/playback/SpotifyProfileCache.kt` — cache followed artists + related artist names
- `app/src/main/kotlin/com/metrolist/music/viewmodels/NewReleaseViewModel.kt` — logica ibrida con 4 fetch paralleli
- `app/src/main/kotlin/com/metrolist/music/models/NewReleaseItem.kt` — campo `isFromFollowedArtist`
- `app/src/main/kotlin/com/metrolist/music/ui/screens/NewReleaseScreen.kt` — UI con ChipsRow e tab
- `app/src/main/res/values/metrolist_strings.xml` — stringhe per tab

### Priorità suggerita: **Alta** (feature visibile e fonte di lamentele)
### Complessità stimata: **Alta**

---

## 4. Android Auto — playlist Spotify non visibili

### Segnalazione
Su Android Auto le playlist sincronizzate da Spotify non compaiono correttamente. Comportamento misto tra utenti.

### Stato attuale
- L'app dichiara il supporto Android Auto in `automotive_app_desc.xml` (`<uses name="media" />`)
- Usa `MediaLibrarySession` con `MediaLibraryService` (Media3) per esporre contenuti
- Il servizio è in `MusicService.kt`
- Android Auto richiede che l'app sia sideloaded con "Unknown sources" nelle impostazioni sviluppatore

### Analisi del problema
Il `MediaLibraryService` deve esporre un albero di contenuti navigabile. Le playlist Spotify devono essere incluse in questo albero. Possibili cause:
1. **L'albero dei media non include le playlist Spotify:** il `MediaLibraryService` potrebbe esporre solo playlist locali/YouTube e non quelle sincronizzate da Spotify
2. **Caricamento asincrono:** le playlist Spotify vengono caricate via rete; se il caricamento è lento, Android Auto potrebbe mostrare un albero vuoto/incompleto
3. **Limiti di Android Auto:** Auto ha limiti sul numero di item per categoria e profondità dell'albero
4. **Formato dei MediaItem:** I metadati delle tracce Spotify potrebbero non essere formattati correttamente per Android Auto (artwork mancante, durata non impostata, ecc.)

### Azioni di debug
1. Verificare il metodo `onGetChildren()` in `MusicService.kt` — controlla se le playlist Spotify sono incluse nell'albero
2. Testare con il Desktop Head Unit (DHU) di Android Auto per avere log dettagliati
3. Controllare se le playlist appaiono dopo un ritardo (problema di caricamento asincrono)
4. Verificare che ogni `MediaItem` esposto abbia tutti i metadati richiesti da Auto

### Priorità suggerita: **Alta** (Android Auto è un caso d'uso importante)
### Complessità stimata: **Media**

### File coinvolti
- `app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt` — albero MediaLibrary
- `app/src/main/res/xml/automotive_app_desc.xml` — dichiarazione supporto

---

## 5. Regressione integrazione Last.fm

### Segnalazione
Un utente riporta che lo scrobbling Last.fm funziona nell'app originale Metrolist ma non nella nostra fork. Possibile regressione introdotta durante le nostre modifiche.

### Stato attuale
L'integrazione Last.fm è composta da:
- **Modulo `lastfm/`:** client API con autenticazione (`auth.getMobileSession`) e metodi `track.scrobble` e `track.updateNowPlaying`
- **`ScrobbleManager.kt`:** gestisce il timing dello scrobbling (soglia: 50% durata o 180s, configurabile)
- **`LastFMSettings.kt`:** UI impostazioni con login, delay percentuale, durata minima, now playing
- **`MusicService.kt` (linee ~750-783):** integrazione nel servizio di riproduzione

### Possibili cause di regressione
1. **Modifiche al `MusicService`:** se abbiamo modificato il flusso di riproduzione o il listener degli eventi, il trigger per lo scrobbling potrebbe non attivarsi più
2. **Cambiamento nei metadati delle tracce:** se il formato dei metadati (titolo, artista, album) è cambiato con le nostre modifiche Spotify, il Last.fm potrebbe ricevere dati malformati o vuoti
3. **Problema di timing:** se abbiamo cambiato il modo in cui gestiamo pause/resume, il contatore dello scrobbling potrebbe non accumularsi correttamente
4. **Errore di autenticazione:** se la chiave API o il secret sono stati modificati o rimossi
5. **ProGuard/R8:** se abbiamo cambiato le regole di obfuscation, classi del modulo `lastfm` potrebbero essere rimosse o rinominate

### Piano di indagine
1. **Controllare git diff** del modulo `lastfm/` e di `ScrobbleManager.kt` rispetto all'upstream
2. **Verificare i log** di scrobbling: aggiungere logging temporaneo in `ScrobbleManager` per tracciare se gli eventi arrivano
3. **Testare autenticazione:** verificare che il login Last.fm funzioni e la sessione sia valida
4. **Confrontare metadati:** stampare i metadati che vengono passati a `track.scrobble` e verificare che siano corretti
5. **Controllare ProGuard rules:** verificare che il modulo `lastfm` non sia offuscato

### Priorità suggerita: **Alta** (regressione rispetto all'app originale — brutta immagine)
### Complessità stimata: **Bassa-Media** (probabilmente un bug puntuale)

### File coinvolti
- `lastfm/src/main/kotlin/com/metrolist/lastfm/LastFM.kt`
- `app/src/main/kotlin/com/metrolist/music/utils/ScrobbleManager.kt`
- `app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt`
- `app/src/main/kotlin/com/metrolist/music/ui/screens/settings/integrations/LastFMSettings.kt`

---

## 6. Analisi buffer della coda e comportamento offline

### Segnalazione
Richiesta di valutare quanto contenuto pre-bufferizzato ("ore caricato") l'app mantiene e quanto a lungo l'utente può ascoltare offline prima che il buffer si esaurisca.

### Stato attuale
Il sistema di caching opera su due livelli:
- **Player Cache (`@PlayerCache`):** cache per lo streaming in tempo reale, gestita da `SimpleCache` di ExoPlayer
- **Download Cache (`@DownloadCache`):** cache per tracce scaricate esplicitamente, separata dalla player cache
- **Persistenza coda:** la coda corrente viene salvata su disco ogni 10 secondi se `PersistentQueueKey` è abilitato
- **Preload:** caricamento progressivo quando la coda ha meno di 5 elementi

### Parametri da analizzare
1. **Dimensione della player cache:** verificare il limite massimo configurato (default ExoPlayer: 100MB, ma potrebbe essere stato customizzato)
2. **Quanto audio entra nella cache:** una traccia media (320kbps) pesa ~5MB per 2 minuti → 100MB ≈ ~40 minuti di musica. A 128kbps circa il doppio
3. **Politica di eviction:** LRU (Least Recently Used) — quando la cache è piena, i contenuti meno recenti vengono rimossi
4. **Tracce scaricate:** queste non vengono evict e restano disponibili offline indefinitamente
5. **Metadati:** anche i metadati (titolo, artwork, ecc.) vengono cachati? Se no, offline le tracce potrebbero apparire senza informazioni

### Azioni raccomandate
1. **Trovare e documentare** la dimensione della player cache nel codice (cercare `SimpleCache` e `CacheDataSource`)
2. **Considerare un'opzione utente** per aumentare la dimensione della cache (es. 100MB / 500MB / 1GB / Illimitata)
3. **Testare scenario offline:** mettere il dispositivo in modalità aereo dopo aver ascoltato N tracce e verificare quante sono riproducibili
4. **Valutare pre-download intelligente:** scaricare in background le prossime N tracce della coda quando si è su WiFi

### Priorità suggerita: **Media**
### Complessità stimata: **Bassa** (per l'analisi), **Media** (per implementare configurabilità)

### File coinvolti
- `app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt` — setup cache
- `app/src/main/kotlin/com/metrolist/music/playback/DownloadUtil.kt` — gestione download
- `app/src/main/kotlin/com/metrolist/music/di/AppModule.kt` — probabile configurazione dimensione cache (Hilt)

---

## 7. Errore riproduzione: "video unavailable, code io_unspecified 2000"

### Segnalazione
Un utente ha riscontrato l'errore `"playback failed, video unavailable, code io_unspecified 2000"` cercando di ascoltare "Raindance" di Dave ft. Tems. Il problema non è riproducibile per noi.

### Analisi del codice errore
- **`io_unspecified` (2000):** è un errore generico di I/O di ExoPlayer. Indica che il player non è riuscito a caricare la risorsa, ma senza una causa specifica
- **"video unavailable":** suggerisce che l'URL dello stream YouTube non era valido o era scaduto

### Possibili cause
1. **Restrizione geografica:** il video potrebbe non essere disponibile nel paese dell'utente
2. **URL stream scaduto:** gli URL di YouTube Music hanno un TTL; se l'app tenta di usare un URL cachato scaduto, fallisce
3. **Problemi di rete:** connessione instabile, timeout, o blocco da parte di ISP/VPN
4. **Rate limiting YouTube:** se l'utente fa molte richieste in poco tempo, YouTube potrebbe bloccare temporaneamente
5. **Versione specifica del video:** la traccia potrebbe avere più versioni su YouTube, e quella selezionata dal matching potrebbe essere non disponibile
6. **Problema con InnerTube:** il modulo `innertube` potrebbe aver ricevuto un errore dall'API che non viene gestito gracefully

### Azioni raccomandate
1. **Migliorare il logging degli errori** di riproduzione: loggare il videoId, la risposta di InnerTube, e lo stato della cache
2. **Implementare retry con fallback:** se una traccia non è riproducibile, tentare con un URL fresco (non cachato)
3. **Verificare gestione degli URL scaduti:** controllare se c'è un meccanismo per rigenerare gli URL prima che scadano
4. **Aggiungere info nel messaggio di errore:** mostrare all'utente informazioni più utili (es. "La traccia potrebbe non essere disponibile nella tua regione")
5. **Chiedere all'utente:** raccogliere informazioni sulla sua regione, VPN, e versione dell'app

### Priorità suggerita: **Bassa** (non riproducibile, probabilmente specifico dell'utente)
### Complessità stimata: **Bassa** (per migliorare l'error handling), **N/A** (per il fix specifico senza riprodurre il bug)

---

## 8. Rischio ban account — valutazione e comunicazione

### Segnalazione
Utenti chiedono se l'uso di Metrolist comporta rischi di ban per i loro account Spotify e/o YouTube/Google.

### Analisi dei rischi

#### Account Spotify
- **Come usiamo Spotify:** L'app si autentica tramite WebView (cookie di sessione), poi usa le API GraphQL di `api-partner.spotify.com` e REST di `api.spotify.com` per leggere dati (playlist, liked songs, artisti, nuove uscite, profilo)
- **Non ascoltiamo musica da Spotify:** lo streaming avviene da YouTube Music, quindi non generiamo stream fraudolenti su Spotify
- **Rischio:** **Basso-Medio**. L'uso di API non ufficiali (GraphQL partner) viola tecnicamente i ToS di Spotify. Tuttavia:
  - Non generiamo stream artificiali
  - Non modifichiamo dati (al momento, la sync è solo in lettura)
  - Il volume di richieste per utente è basso
  - Spotify generalmente non banna per uso di client non ufficiali in lettura
- **Precedenti:** Client come Spicetify e SpotDL operano da anni senza ban massivi. Tuttavia, Spotify ha iniziato a essere più aggressiva nel 2024-2025

#### Account YouTube/Google
- **Come usiamo YouTube:** tramite il modulo `innertube` che simula un client YouTube Music per ottenere URL di streaming
- **Rischio:** **Medio**. Google è storicamente più aggressiva contro l'uso non autorizzato delle API:
  - Il modulo `innertube` impersona un client YouTube Music ufficiale
  - Google potrebbe rilevare pattern anomali (assenza di pubblicità, user agent non standard)
  - Rischio maggiore se l'utente è loggato con il proprio account Google
  - Progetti simili (NewPipe, Invidious) hanno subito pressioni da Google
- **Mitigazione:** l'uso senza login Google riduce significativamente il rischio

### Raccomandazioni
1. **Aggiungere un disclaimer nel README** che informa gli utenti dei potenziali rischi
2. **Suggerire di usare account secondari** per ridurre il rischio sul proprio account principale
3. **Implementare rate limiting** nelle chiamate API per apparire più "naturali"
4. **Non incoraggiare il login Google** nell'app se non strettamente necessario
5. **Monitorare** report di ban dalla community e aggiornare le raccomandazioni di conseguenza

### Priorità suggerita: **Alta** (comunicazione trasparente con gli utenti)
### Complessità stimata: **Bassa** (per la comunicazione), **Media** (per implementare mitigazioni tecniche)

---

## 9. Supporto file audio locali

### Segnalazione
Utenti chiedono se è possibile aggiungere file audio locali (dal dispositivo) all'app, similmente a quanto fanno YouTube Music e Spotify, specialmente per canzoni non rilasciate ufficialmente.

### Cosa intende la richiesta
Sia YouTube Music che Spotify permettono di aggiungere alla libreria file musicali presenti sul dispositivo:
- **YouTube Music:** permette di caricare fino a 100.000 brani dal computer, che diventano disponibili su tutti i dispositivi. Su mobile, permette di riprodurre file audio locali presenti sul dispositivo
- **Spotify:** ha una funzione "Local Files" che scansiona il dispositivo alla ricerca di file audio (.mp3, .m4a, .flac, ecc.) e li integra nella libreria. Possono essere aggiunti a playlist e riprodotti come qualsiasi altra traccia. Non vengono caricati sui server, restano locali

In pratica, l'utente ha brani che **non esistono su nessuna piattaforma di streaming** (demo, bootleg, registrazioni live, tracce non rilasciate, produzioni proprie) e vorrebbe poterli ascoltare nella stessa app insieme al catalogo YouTube Music.

### Stato attuale in Metrolist
L'app **non supporta** file audio locali del dispositivo. Le funzionalità "locali" esistenti sono:
- **Download/Cache:** tracce scaricate da YouTube Music per ascolto offline (non file esterni)
- **Playlist locali:** playlist create nell'app (ma contengono solo tracce YouTube)

### Analisi tecnica dell'implementazione
1. **Scanner file system:** serve un `MediaScanner` che trovi file audio sul dispositivo
   - Permessi necessari: `READ_MEDIA_AUDIO` (Android 13+) o `READ_EXTERNAL_STORAGE` (versioni precedenti)
   - Utilizzare `MediaStore` di Android per scansionare efficientemente
2. **Player locale:** ExoPlayer (Media3) supporta nativamente la riproduzione di file locali — non serve un player diverso
3. **Integrazione nel database:** aggiungere un flag `isLocal` a `SongEntity` e gestire le tracce locali nel database Room
4. **Integrazione nelle playlist:** permettere di mescolare tracce locali e tracce YouTube nelle stesse playlist
5. **UI:** aggiungere una sezione "File locali" nella libreria con possibilità di scansione/refresh
6. **Metadati:** leggere tag ID3/Vorbis per titolo, artista, album, artwork. Libreria suggerita: nessuna aggiuntiva, `MediaMetadataRetriever` di Android o `MediaStore` è sufficiente
7. **Artwork:** se il file ha un artwork embedded, usarlo; altrimenti tentare un match con artwork da YouTube/Spotify

### Sfide
- **Complessità UI:** integrare due tipi di sorgente audio richiede modifiche diffuse nell'UI
- **Coda mista:** la coda deve gestire sia `MediaItem` da YouTube (con URL da rigenerare) che `MediaItem` locali (con URI file)
- **Android Auto:** i file locali devono essere esposti anche nell'albero `MediaLibraryService`
- **Permessi:** la richiesta di permessi al file system potrebbe confondere gli utenti o sembrare invasiva

### Priorità suggerita: **Bassa-Media** (feature richiesta ma di nicchia)
### Complessità stimata: **Alta**

### File coinvolti (potenziali)
- `app/src/main/kotlin/com/metrolist/music/db/entities/SongEntity.kt` — aggiungere campo `isLocal` e `localUri`
- `app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt` — supporto riproduzione locale
- `app/src/main/kotlin/com/metrolist/music/playback/queues/` — nuova `LocalQueue` o estensione delle code esistenti
- Nuova UI screen per browsing file locali
- `AndroidManifest.xml` — permessi

---

## 10. Sezioni Home biased verso liked songs recenti

### Segnalazione
Le sezioni "Your Top Tracks" e "Because you like [Artista]" nella Home sembrano mostrare solo le ultime canzoni a cui l'utente ha messo "Mi piace", anziché le tracce effettivamente più ascoltate.

### Stato attuale — PROBLEMA CONFERMATO
La `SpotifyProfileCache` usa un sistema a 3 livelli di fallback per popolare queste sezioni:

**Tier 1 — Liked Songs (GQL, nessun rate limit):**
Recupera le ultime 100 canzoni "Mi piace" dell'utente ordinate per data di aggiunta (le più recenti prima). Funziona quasi sempre perché usa l'endpoint GraphQL `fetchLibraryTracks` che non ha rate limiting aggressivo.

**Tier 2 — Top Tracks reali (REST API Spotify):**
Chiama `GET api.spotify.com/v1/me/top/tracks?time_range=short_term&limit=50`, che restituirebbe le tracce realmente più ascoltate. **Ma fallisce frequentemente** per:
- Timeout di soli **3 secondi** (`REST_CALL_TIMEOUT_MS = 3000L`) — troppo aggressivo
- Rate limit 429 molto frequente per app in "development mode" su Spotify
- Cooldown di **5 minuti** (`REST_COOLDOWN_MS`) dopo un singolo errore, durante il quale non si ritenta

**Tier 3 — Database locale (query `mostPlayedSongs`):**
Si attiva **solo se `tracks` è completamente vuoto** (cioè solo se anche il Tier 1 GQL fallisce), il che è quasi impossibile.

### Flusso del problema

```
Tier 1 (liked songs GQL) → quasi sempre successo → lista piena di liked songs recenti
Tier 2 (REST top tracks) → spesso fallisce (timeout/429) → nessun merge con dati reali
Tier 3 (DB locale) → mai raggiunto perché Tier 1 ha già popolato la lista
Cache (6 ore TTL) → cristallizza dati degradati per 6 ore
```

**Risultato:** "Your Top Tracks" mostra le ultime 20 canzoni likate, non le più ascoltate.

### Impatto su ogni sezione

| Sezione | Comportamento ideale | Comportamento effettivo (REST fallito) | Gravità bias |
|---|---|---|---|
| **Your Top Tracks** | Tracce più ascoltate (short_term) | Ultime 20 liked songs per data | **Forte** |
| **Because you like [Artista]** | Top tracks di un artista che ascolti molto | Top tracks dell'artista con più like recenti | **Moderato** (le tracks mostrate sono comunque le top globali dell'artista, ma l'artista scelto è sbagliato) |
| **Made For You** | Raccomandazioni basate su gusti reali | Liked songs dalla posizione 21+ | **Forte** |

### Cause principali
1. **`REST_CALL_TIMEOUT_MS = 3000L`** — 3 secondi sono insufficienti per le REST API Spotify, specialmente su reti mobili
2. **`REST_COOLDOWN_MS = 5 * 60 * 1000`** — 5 minuti di blackout dopo un singolo errore è eccessivo
3. **Liked songs come "proxy"** — il commento nel codice le definisce "good proxy for preferences", ma ordinate per data di aggiunta sono un proxy molto debole per i gusti reali
4. **Solo `short_term`** — il Tier 2 chiede solo le top tracks delle ultime ~4 settimane, perdendo `medium_term` e `long_term`
5. **Cache 6 ore senza invalidazione** — se il primo caricamento è degradato, lo rimane per ore
6. **Tier 3 irraggiungibile** — il fallback su `mostPlayedSongs` del DB locale (che sarebbe il dato più accurato disponibile) non viene mai usato perché il Tier 1 riempie sempre la lista

### Fix proposti

**Fix immediati (bassa complessità):**
1. Aumentare `REST_CALL_TIMEOUT_MS` a **8-10 secondi**
2. Ridurre `REST_COOLDOWN_MS` a **60 secondi**
3. Aggiungere retry (almeno 1) per il Tier 2 REST

**Fix strutturali (media complessità):**
4. Usare il Tier 3 (DB locale `mostPlayedSongs`) come **fallback attivo** anche quando il Tier 1 ha dati — non solo come ultima risorsa. Se REST fallisce, fare un merge ponderato tra liked songs e tracce più ascoltate localmente
5. Aggiungere `medium_term` come secondo time range nel Tier 2 per dati più stabili
6. Differenziare il TTL della cache: 6 ore per dati completi (Tier 2 ok), 30 minuti per dati degradati (solo Tier 1)
7. Ordinare le liked songs del Tier 1 per frequenza locale di ascolto (join con tabella eventi) anziché per data di aggiunta

**Fix ottimali (alta complessità):**
8. Implementare un sistema di scoring composito: `score = (restTopRank * 2) + (localPlayCount * 1.5) + (likedRecency * 0.5)` per ordinare le tracce in modo realmente rappresentativo dei gusti dell'utente

### Priorità suggerita: **Alta** (bug percepibile dall'utente, degrada l'esperienza Home)
### Complessità stimata: **Bassa** (fix immediati), **Media** (fix strutturali)

### File coinvolti
- `app/src/main/kotlin/com/metrolist/music/playback/SpotifyProfileCache.kt` — logica dei 3 tier e costanti timeout/cooldown
- `app/src/main/kotlin/com/metrolist/music/viewmodels/HomeViewModel.kt` — costruzione sezioni Home (righe ~600-650)

### Stato dei fix — COMPLETATO
I fix immediati (timeout 3s→8s, cooldown 5min→1min, retry REST, Tier 3 attivo con riordino per play count locale, cache TTL differenziato 6h/30min) sono stati implementati nel branch `developement`. Build verificata con successo.

---

## 11. Endpoint GQL alternativi per aggirare il rate limiting REST di Spotify

### Contesto
Le REST API Spotify (`me/top/tracks`, `me/top/artists`) sono pesantemente rate-limitate (429) per le app in "development mode". Questo è il motivo principale del problema #10. Servizi esterni come statsforspotify.com non sono una soluzione: usano le stesse identiche API REST, non espongono API pubbliche, e richiederebbero una seconda autenticazione utente.

### Opportunità: endpoint GQL non sfruttati
L'app usa già `api-partner.spotify.com/pathfinder/v2/query` (GraphQL) per molte operazioni (liked songs, playlist, nuove uscite, artisti), e **questi endpoint non hanno rate limiting aggressivo**. Il web player di Spotify (`open.spotify.com`) popola la propria home page con sezioni personalizzate usando interamente GQL. Operazioni rilevanti che possiamo esplorare:

| Operazione GQL | Cosa restituisce | Rate limit | Utilità |
|---|---|---|---|
| `fetchRecentlyPlayed` | Tracce ascoltate di recente | Nessuno (GQL) | Proxy migliore delle liked songs per frequenza di ascolto |
| `queryHome` / `homeV2` | Feed personalizzato della home Spotify | Nessuno (GQL) | Contiene nativamente sezioni "Top Tracks", "Made For You" |
| `personalizedRecommendations` | Raccomandazioni basate sull'ascolto | Nessuno (GQL) | Alternativa a `me/recommendations` REST |
| `me/player/recently-played` | Ultime 50 tracce ascoltate | REST ma meno aggressivo | Sorgente supplementare nel Tier 2 |

### Come implementare
Ogni operazione GQL richiede un **sha256 hash** che identifica la query precompilata. Per estrarre gli hash:
1. Aprire `open.spotify.com` in un browser con DevTools → Network
2. Filtrare per `pathfinder` nelle richieste
3. Intercettare le chiamate GQL e copiare `sha256Hash` e `operationName` dal body
4. In alternativa, decompilare il bundle JS del web player Spotify per estrarre tutti gli hash disponibili

### Strategia di integrazione
1. **Fase 1 (basso rischio):** aggiungere `me/player/recently-played` (REST, meno limitata) come sorgente supplementare nel Tier 2 di `SpotifyProfileCache`
2. **Fase 2:** estrarre hash per `fetchRecentlyPlayed` (GQL) e aggiungerlo come nuovo Tier 1.5 — questo eliminerebbe quasi del tutto la dipendenza dal REST
3. **Fase 3:** esplorare `queryHome` per recuperare intere sezioni personalizzate direttamente da Spotify senza doverle ricostruire

### Priorità suggerita: **Media** (miglioramento incrementale sopra i fix già applicati)
### Complessità stimata: **Media** (fase 1 semplice, fase 2-3 richiede estrazione hash)

### File coinvolti
- `spotify/src/main/kotlin/com/metrolist/spotify/Spotify.kt` — nuovi endpoint
- `app/src/main/kotlin/com/metrolist/music/playback/SpotifyProfileCache.kt` — integrazione nei tier

---

## 12. Override manuale del matching Spotify → YouTube

### Segnalazione
Un utente riporta che "1,2,3,4" di Alan Doyle viene matchata alla versione live invece che a quella in studio. Lo stesso accade con altri brani che esistono in più versioni su YouTube (live, acustica, remaster, cover). Il matching fuzzy dell'app non sempre sceglie la versione corretta.

### Stato attuale
Il matching avviene in `SpotifyYouTubeMapper`:
1. Cerca su YouTube Music con titolo + artista
2. Calcola un punteggio basato su similarità titolo, artista e durata
3. Sceglie il candidato con punteggio più alto (soglia minima: 0.35)
4. Salva il match in `SpotifyMatchEntity` (cache persistente nel DB Room)

Il problema: quando esistono più versioni di una canzone (studio, live, acoustic, remix), il matching potrebbe preferire quella sbagliata, specialmente se la versione live ha una durata più simile o compare prima nei risultati di ricerca.

### Stato dei fix — COMPLETATO

Implementato nel branch `developement`. L'utente può ora correggere manualmente un match errato incollando il link YouTube corretto.

**Modifiche implementate:**

1. **Database:**
   - Aggiunto campo `isManualOverride: Boolean` a `SpotifyMatchEntity` con `@ColumnInfo(defaultValue = "0")`
   - Bump versione DB 33→34 con `AutoMigration`
   - Aggiunta query `getSpotifyMatchByYouTubeId()` per reverse lookup e `deleteSpotifyMatch()` per reset

2. **Mapper:**
   - `SpotifyYouTubeMapper.mapToYouTube()` ritorna immediatamente i match con `isManualOverride = true`, senza mai sovrascriverli
   - Aggiunta funzione `overrideMatch()` che salva un match manuale con `matchScore = 1.0` e `isManualOverride = true`

3. **UI — YouTubeMatchDialog** (`ui/component/YouTubeMatchDialog.kt`):
   - Mostra il match corrente in alto con thumbnail, titolo, artista e link YouTube
   - Input field per incollare il nuovo URL YouTube/YouTube Music
   - Preview automatica del nuovo video con metadata
   - Validazione: il bottone OK è disabilitato se il video è lo stesso del match corrente
   - Supporta URL `youtube.com/watch?v=...`, `youtu.be/...`, `music.youtube.com/watch?v=...` e video ID diretto

4. **Entry point — 3 punti di accesso:**
   - **PlayerMenu (tre puntini del player):** punto di accesso principale. Appare solo se il brano in riproduzione ha un match Spotify. Dopo l'override, la riproduzione passa automaticamente alla nuova versione (`playNext` + `seekToNext`)
   - **SongMenu (menu contestuale di qualsiasi brano):** appare ovunque nell'app (home, album, coda, libreria) se il brano ha un match Spotify associato (reverse lookup `getSpotifyMatchByYouTubeId`)
   - **Long-press nelle playlist Spotify:** nelle schermate `SpotifyPlaylistScreen` e `SpotifyLikedSongsScreen`, long-press su un brano apre direttamente il dialog di override (per brani non ancora riprodotti)

### File coinvolti
- `app/src/main/kotlin/com/metrolist/music/db/entities/SpotifyMatchEntity.kt` — campo `isManualOverride`
- `app/src/main/kotlin/com/metrolist/music/db/MusicDatabase.kt` — versione 34, auto-migration 33→34
- `app/src/main/kotlin/com/metrolist/music/db/DatabaseDao.kt` — query `getSpotifyMatchByYouTubeId`, `deleteSpotifyMatch`
- `app/src/main/kotlin/com/metrolist/music/playback/SpotifyYouTubeMapper.kt` — protezione override, `overrideMatch()`
- `app/src/main/kotlin/com/metrolist/music/ui/component/YouTubeMatchDialog.kt` — nuovo dialog
- `app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt` — opzione + playback swap
- `app/src/main/kotlin/com/metrolist/music/ui/menu/SongMenu.kt` — opzione nel menu contestuale
- `app/src/main/kotlin/com/metrolist/music/ui/screens/playlist/SpotifyPlaylistScreen.kt` — long-press
- `app/src/main/kotlin/com/metrolist/music/ui/screens/playlist/SpotifyLikedSongsScreen.kt` — long-press
- `app/src/main/res/values/metrolist_strings.xml` — stringhe per il dialog

---

## Riepilogo priorità

| # | Argomento | Priorità | Complessità | Tipo |
|---|-----------|----------|-------------|------|
| 5 | ~~Regressione Last.fm~~ | ~~**Alta**~~ | ~~Bassa-Media~~ | ~~Bug fix~~ **COMPLETATO** |
| 3 | ~~Nuove Uscite — integrazione ibrida~~ | ~~**Alta**~~ | ~~Alta~~ | ~~Feature~~ **COMPLETATO** |
| 4 | Android Auto — playlist Spotify | **Alta** | Media | Bug fix |
| 8 | ~~Rischio ban — comunicazione~~ | ~~**Alta**~~ | ~~Bassa~~ | ~~Comunicazione~~ **COMPLETATO** |
| 1 | Sync like bidirezionale Spotify | **Media** | Media-Alta | Feature |
| 2 | Sync artisti seguiti e feed | **Media** | Media | Feature |
| 6 | Analisi buffer coda/offline | **Media** | Bassa-Media | Analisi/Feature |
| 7 | Errore riproduzione io_unspecified | **Bassa** | Bassa | Bug fix |
| 9 | File audio locali | **Bassa-Media** | Alta | Feature |
| 10 | ~~Sezioni Home biased (liked songs)~~ | ~~**Alta**~~ | ~~Bassa-Media~~ | ~~Bug fix~~ **COMPLETATO** |
| 11 | Endpoint GQL alternativi (rate limit) | **Media** | Media | Miglioramento |
| 12 | ~~Override manuale matching Spotify→YT~~ | ~~**Media**~~ | ~~Media~~ | ~~Feature~~ **COMPLETATO** |

### Ordine di lavoro suggerito
1. ~~**Sezioni Home biased** (#10) — fix immediati (timeout/cooldown) rapidi e ad alto impatto UX~~ **COMPLETATO**
2. ~~**Last.fm** (#5) — regressione, urgente, probabilmente fix rapido~~ **COMPLETATO** (ProGuard rules, BuildConfig keys, ScrobbleManager duration, LastFM null safety, REST fail-fast 429)
3. ~~**Rischio ban** (#8) — disclaimer nel README, rapido e importante per la fiducia degli utenti~~ **COMPLETATO**
4. ~~**Override manuale matching** (#12) — migliora fiducia utente, media complessità, buon rapporto effort/impatto~~ **COMPLETATO** (dialog URL paste, PlayerMenu + SongMenu + long-press Spotify playlists, playback swap automatico)
5. ~~**Nuove Uscite** (#3) — feature richiesta, alta visibilità~~ **COMPLETATO** (integrazione ibrida Spotify + YouTube, tab Following/Discover/For You, related artists GQL cache 24h)
6. **Buffer/Offline** (#6) — analisi prima, poi eventuale feature ← **PROSSIMO**
7. **Sync like** (#1) e **Artisti seguiti** (#2) — feature importanti ma non urgenti
8. **Endpoint GQL alternativi** (#11) — miglioramento incrementale sopra i fix #10, quando serve
9. **Errore riproduzione** (#7) — monitorare, non riproducibile
10. **File locali** (#9) — valutare dopo aver stabilizzato le feature esistenti
11. **Android Auto** (#4) — implementazione nel branch `fix/android-auto-spotify-playlists`, richiede test con DHU
