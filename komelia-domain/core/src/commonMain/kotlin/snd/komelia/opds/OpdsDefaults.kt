package snd.komelia.opds

/**
 * The address pre-filled everywhere a catalogue has not been configured yet.
 *
 * Nobody's Calibre-Web is on localhost from a phone, so this is never right — it
 * is there to be edited. It says what the answer looks like: the port and the
 * `/opds` suffix are the two parts people get wrong, and editing an address is
 * quicker than writing one from nothing.
 *
 * It replaces Komga's `http://localhost:25600`, which this fork inherited and
 * which pointed at the wrong software entirely. Kept in one place so the login
 * screen, the settings default and the catalogue screen cannot drift apart
 * again — they had, and two of the three still said 25600.
 */
const val DEFAULT_OPDS_URL: String = "http://localhost:8083/opds"

/** The same shape with a routable host, for placeholders that sit next to a filled field. */
const val DEFAULT_OPDS_URL_HINT: String = "http://192.168.x.x:8083/opds"
