package app.stremiolite.linux

import app.stremiolite.Http

fun main(args: Array<String>) {
    if (args.contains("--version")) {
        println("Stremonino for Linux, build ${Updates.current}")
        return
    }
    Http.userAgent = "Stremonino/${Updates.current} (Linux)"
    Profiles.start()
    app.stremiolite.Guide.dir = java.io.File(Store.dir, "guides")
    App.run(
        when {
            Profiles.all().size > 1 -> Profiles.chooser()
            Account.authKey != null -> Home.screen()
            else -> SignIn.screen()
        }
    )
}
