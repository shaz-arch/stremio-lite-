package app.stremiolite.linux

import app.stremiolite.Http

fun main(args: Array<String>) {
    if (args.contains("--version")) {
        println("Stremonino for Linux, build ${Updates.current}")
        return
    }
    Http.userAgent = "Stremonino/${Updates.current} (Linux)"
    App.run(if (Account.authKey != null) Home.screen() else SignIn.screen())
}
