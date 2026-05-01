// Stand-alone from the main Releaf Android build so the spike cannot leak
// into the app's dependency graph. Delete this directory once the production
// editor lands if the assertions get promoted into an androidTest suite.
rootProject.name = "markdown-roundtrip"
