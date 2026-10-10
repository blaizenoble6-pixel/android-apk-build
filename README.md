# Reel Recipes (Android)

Save recipes straight from Instagram's Share menu.

On a post or reel: **Share → Share to… / More → Save recipe**. The app grabs the link, tries to read the caption and cover image from Instagram, lays the recipe out as ingredients and method for you to check, and saves it on your phone with its photos. **Watch ↗** reopens the original video in Instagram.

## How it reads recipes

When you share a post, the app opens it in a built-in browser view (shown on screen as a preview), then:

1. **Caption and photos:** reads the caption, the creator's @handle and every photo in the post (all carousel slides when Instagram provides them), and saves the photos to the recipe.
2. **Text in images:** reads any text in those photos and in screenshots you add, on the phone (Google ML Kit, offline, free). Recipe cards in carousels and text overlays on reel frames come through this way.
3. **Recipe:** splits everything into ingredients (with amounts) and method, then opens it for you to check and save.

No API key is needed for any of that.

- **Sign in to Instagram (Settings, optional):** Instagram shows less to signed-out visitors. Signing in once in the app's Instagram page makes captions and photos come through for far more posts. You sign in on Instagram's own page; the app only keeps the sign-in cookie.
- **Anthropic API key (Settings, optional):** Claude tidies messy captions, estimates missing amounts and understands photos without text. Billed by Anthropic per use, separately from a Claude subscription.
- **Reel videos:** the app can't download the video itself. If the recipe only appears in the video, take screenshots of those frames and add them.

Recipes stay on the phone (`recipes.json` + a `photos` folder). They don't sync with the Reel Recipes web page.

## Updating

Builds are signed with `app/reelrecipes-debug.keystore`, so a new APK installs straight over the old one and keeps your recipes. (Keep that file in the repo. If it's missing, builds still work but each one needs an uninstall first.)

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
  PostReader.kt     opens posts in a WebView and pulls caption, @handle, photo URLs
  Ocr.kt            on-phone text recognition (ML Kit)
  Instagram.kt      link cleaning + fallback caption/cover fetch
  Extractor.kt      on-phone recipe parser and the optional Claude API call
  Recipe.kt         data model and JSON
  RecipeStore.kt    on-phone storage, photos, settings
  ui/App.kt         screens: shelf, recipe, import, edit, settings
  ui/Theme.kt       colours (match the web app)
```
Requirements: minSdk 26 (Android 8), Kotlin 2.0, Jetpack Compose, Gradle 8.9, JDK 17.
