package app.stremiolite.linux

import app.stremiolite.Http

fun main(args: Array<String>) {
    if (args.contains("--version")) {
        println("Stremio Lean for Linux, build ${Updates.current}")
        return
    }
    Http.userAgent = "StremioLean/${Updates.current} (Linux)"
    App.run(if (Account.authKey != null) Home.screen() else SignIn.screen())
}
