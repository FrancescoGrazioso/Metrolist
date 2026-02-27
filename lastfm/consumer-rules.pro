# Keep the LastFM singleton and all its public/private members
-keep class com.metrolist.lastfm.LastFM { *; }
-keep class com.metrolist.lastfm.LastFM$LastFmException { *; }

# Keep serializable model classes used for JSON parsing
-keep class com.metrolist.lastfm.models.** { *; }
