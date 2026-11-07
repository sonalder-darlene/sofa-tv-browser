package app.sofabrowser.ui

import app.sofabrowser.R

/**
 * The addresses Settings → Support Sofa shows, each with its QR code. The codes are made by
 * tools/render-donate-qr.sh, which holds the same list; the README shows it too (DonationsTest checks all three).
 */
object Donations {
    class Coin(val name: Int, val note: Int?, val address: String, val qr: Int)

    val coins = listOf(
        Coin(R.string.donate_btc, R.string.donate_btc_note,
            "sp1qqglkmsmm6fkxzmnjtzzlyfshur2w80zlmaddvq497lesc569aahekqe9h5fvpjn53yz9hfjgc5f872acxx8pqf2jp8pypl3vth4ten4a3s5p9jrv",
            R.drawable.donate_qr_btc),
        Coin(R.string.donate_lightning, R.string.donate_lightning_note, "sonalder@cake.cash", R.drawable.donate_qr_lightning),
        Coin(R.string.donate_xmr, null,
            "469dA6keA9w9EfensKZJnaZCmqQY4foXedwnN98Sm7QVeJeXTMR7EAU913oPqzFRVJes8uyYQWixm6oqH3i37Vyf1NbomdG",
            R.drawable.donate_qr_xmr),
        Coin(R.string.donate_zec, R.string.donate_zec_note,
            "u1paq3l7dms04sml8r4gp40hdh8wu73hlj70rafmyay23rx2rr6kynx2t0dgge2m6nsu6rcsl4xnhp255t3qv062jfrrw3jx4uygkasts8",
            R.drawable.donate_qr_zec),
        Coin(R.string.donate_eth, null, "0xC88455aa2b489Ba83Ed9606EA9627466C37C4D5a", R.drawable.donate_qr_eth),
    )

    /** A long address cut to its first and last characters, for a row's subtitle. */
    fun short(address: String): String = if (address.length <= 24) address else address.take(12) + "…" + address.takeLast(8)
}
