# Reel Recipes (Android)

Save recipes straight from Instagram's Share menu.

On a post or reel: **Share → Share to… / More → Save recipe**. The app grabs the link, tries to read the caption and cover image from Instagram, lays the recipe out as ingredients and method for you to check, and saves it on your phone with its photos. **Watch ↗** reopens the original video in Instagram.

## How it reads recipes

- **Caption fetch:** Instagram has no public API for this, so the app reads the same public page a logged-out browser sees. That works for most public posts. When Instagram blocks it, copy the caption in yourself or add screenshots.
- **No API key (default):** an on-phone parser splits neatly laid-out captions ("Ingredients:" … "Method:") into a recipe. Free and offline.
- **With an Anthropic API key (Settings):** Claude reads messy captions and screenshots of the video. Billed by Anthropic per use, separately from a Claude subscription. Get a key at console.anthropic.com.

Recipes stay on the phone (`recipes.json` + a `photos` folder). They don't sync with the Reel Recipes web page.

## Build it

### Option A: GitHub builds it for you (no Android Studio)
1. Create a new private repository on GitHub and upload this folder's contents (keep the `.github` folder).
2. Open the **Actions** tab. The *Build APK* workflow runs on every push (or press *Run workflow*).
3. When it finishes, open the run and download **reel-recipes-apk**. Unzip it to get `app-debug.apk`.
4. Copy the APK to your phone, open it, and allow "Install unknown apps" for your file manager when asked.

### Option B: Android Studio
1. Open this folder in Android Studio (Koala or newer) and let Gradle sync.
2. Plug in your phone with USB debugging on and press **Run**.

## Project layout
```
app/src/main/java/com/reelrecipes/app/
  MainActivity.kt   receives shares from Instagram / gallery
  Instagram.kt      link cleaning + public caption/cover fetch
  Extractor.kt      Claude API call and the offline caption parser
  Recipe.kt         data model and JSON
  RecipeStore.kt    on-phone storage, photos, settings
  ui/App.kt         screens: shelf, recipe, import, edit, settings
  ui/Theme.kt       colours (match the web app)
```
Requirements: minSdk 26 (Android 8), Kotlin 2.0, Jetpack Compose, Gradle 8.9, JDK 17.
