package com.fp.ytmp3

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataTest {

    @Test
    fun itunesPicksTheStudioTrack() {
        val json = """
            {"results":[
              {"artistName":"Ben Harper","trackName":"Jah Work",
               "collectionName":"The Will to Live","releaseDate":"1997-01-01T12:00:00Z",
               "artworkUrl100":"https://example/100x100bb.jpg"},
              {"artistName":"Ben Harper & The Innocent Criminals",
               "trackName":"Jah Work (A Lewis Marnell Tribute)",
               "collectionName":"Jah Work (A Lewis Marnell Tribute) - Single",
               "releaseDate":"2013-04-30T07:00:00Z",
               "artworkUrl100":"https://example/other/100x100bb.jpg"}
            ]}
        """
        val meta = MusicBrainz.parseItunes(json, "Ben Harper Jah work", "noliv008")
        assertEquals("Ben Harper", meta?.artist)
        assertEquals("Jah Work", meta?.title)
        assertEquals(1997, meta?.year)
        assertEquals("The Will to Live", meta?.album)
        assertTrue(meta?.coverUrl?.contains("600x600bb") == true)
    }

    @Test
    fun deezerPicksTheStudioTrack() {
        val json = """
            {"data":[
              {"title":"Jah Work",
               "artist":{"name":"Ben Harper"},
               "album":{"id":301774,"title":"The Will To Live",
                        "cover_xl":"https://example/cover.jpg"}},
              {"title":"Jah Work (A Lewis Marnell Tribute)",
               "artist":{"name":"Ben Harper"},
               "album":{"id":67896882,"title":"Jah Work (A Lewis Marnell Tribute)",
                        "cover_xl":"https://example/tribute.jpg"}}
            ]}
        """
        val dz = MusicBrainz.parseDeezer(json, "Ben Harper Jah work", "noliv008")
        assertEquals("Ben Harper", dz?.meta?.artist)
        assertEquals("Jah Work", dz?.meta?.title)
        assertEquals(301774L, dz?.albumId)
    }

    @Test
    fun releasePrefersStudioAlbumOverLiveAndSingle() {
        val releases = JSONArray("""
            [
              {"date":"1999-07-18","release-group":{"id":"live","title":"Live aux Vieilles Charrues",
                "primary-type":"Album","secondary-types":["Live"]}},
              {"date":"1997","release-group":{"id":"studio","title":"The Will to Live",
                "primary-type":"Album"}},
              {"date":"2000-06-02","release-group":{"id":"single","title":"Jah Work",
                "primary-type":"Single"}}
            ]
        """)
        val rel = MusicBrainz.pickRelease(releases)
        assertEquals("The Will to Live", rel.album)
        assertEquals(1997, rel.year)
        assertEquals("studio", rel.rgid)
    }

    @Test
    fun soundtrackDoesNotBeatAStudioAlbumEvenIfOlder() {
        val releases = JSONArray("""
            [
              {"date":"2013-10-08","release-group":{"id":"cbgb","title":"CBGB: Original Motion Picture Soundtrack",
                "primary-type":"Album","secondary-types":["Soundtrack"]}},
              {"date":"2014-01-01","release-group":{"id":"studio","title":"Studio Album",
                "primary-type":"Album"}}
            ]
        """)
        val rel = MusicBrainz.pickRelease(releases)
        assertEquals("Studio Album", rel.album)
        assertEquals(2014, rel.year)
        assertEquals("studio", rel.rgid)
    }

    @Test
    fun matchingRejectsUnknownArtist() {
        assertFalse(MusicBrainz.matches("Me at the zoo", "jawed", "Some Artist", "Me at the Zoo"))
        assertTrue(MusicBrainz.matches("Me at the zoo", "jawed", "jawed", "Me at the Zoo"))
        assertFalse(MusicBrainz.matches("Jah Work", "noliv008", "Utan Green", "Jah Work"))
        assertTrue(MusicBrainz.matches("Ben Harper Jah work", "noliv008", "Ben Harper", "Jah Work"))
        assertTrue(MusicBrainz.matches("Jah Work", "Ben Harper - Topic", "Ben Harper", "Jah Work"))
    }

    @Test
    fun apostropheInCatalogTitleMatchesFileWithoutIt() {
        assertTrue(
            MusicBrainz.matches(
                "Papas Got a Brand New Bag", "Otis Redding",
                "Otis Redding", "Papa's Got a Brand New Bag"
            )
        )
    }

    @Test
    fun liveSuffixDoesNotBlockConfirmedArtist() {
        assertTrue(
            MusicBrainz.matches(
                "Papas Got a Brand New Bag", "Otis Redding",
                "Otis Redding", "Papa's Got a Brand New Bag (Live)"
            )
        )
    }

    @Test
    fun fileWithPapasMatchesTitleWithApostrophe() {
        val fp = NameMatcher.prepare(
            listOf("1966 - Otis Redding - Papas Got a Brand New Bag.mp3")
        )
        assertEquals(0, NameMatcher.indexOfMatch("Otis Redding - Papa's Got a Brand New Bag", fp))
    }

    @Test
    fun noiseOnlyTitleStillMatches() {
        assertTrue(MusicBrainz.matches("Music", "Madonna", "Madonna", "Music"))
    }
}
