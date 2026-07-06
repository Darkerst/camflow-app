# CamFlow

Volledig native camera-app: live inline preview (geen externe camera-app die opent), foto + video opnemen, automatisch opslaan in de Galerij, front/back switch, eigen app-icoon.

## Wat is er anders dan de vorige versie (CameraNativeApp)?

De vorige versie opende de systeem-camera-app via een intent en gaf het resultaat terug. Deze versie gebruikt **CameraX rechtstreeks**: de camera-preview draait live ín de app zelf (`PreviewView`), en foto's/video's worden direct vanuit de app-code vastgelegd — geen omweg via een andere app.

## Features

- Live camera-preview, direct bij openen van de app
- Foto/Video-toggle bovenaan
- Automatisch opslaan: foto's naar `Pictures/CamFlow`, video's naar `Movies/CamFlow` (zichtbaar in de Galerij-app)
- Front/back camera wisselen
- REC-indicator tijdens video-opname
- Eigen app-icoon en naam ("CamFlow") in plaats van het standaard Android-icoontje

## Bouwen via GitHub Actions (zelfde route als eerder)

1. Nieuwe (of dezelfde) GitHub-repository
2. Push deze map:
   ```bash
   cd CameraFullNative
   git init
   git add .
   git commit -m "CamFlow native app"
   git branch -M main
   git remote add origin https://github.com/JOUW-GEBRUIKERSNAAM/JOUW-REPO.git
   git push -u origin main
   ```
3. Tab **Actions** → wacht tot de run groen is
4. **Artifacts** → download `camflow-debug-apk`
5. APK naar je telefoon, installeren

Dit project heeft **gradle.properties** met `android.useAndroidX=true` en een vastgezette Gradle-versie (8.7) al vanaf het begin ingebouwd — beide problemen die je vorige keer tegenkwam zijn hier al opgelost.

## Bestanden

- `MainActivity.kt` — gebruikt `ComponentActivity` (niet `AppCompatActivity`) om de eerdere theme-crash te vermijden
- `CameraScreen.kt` — alle camera-logica: live preview, foto/video-capture, MediaStore-opslag
- `res/mipmap-*/ic_launcher.png` — het eigen app-icoon, gegenereerd op alle benodigde schermdichtheden
- `res/values/strings.xml` — app-naam ("CamFlow")

## Uitbreiden

Wil je zoom-gebaren, flitser-toggle, of een galerijweergave binnen de app zelf van eerder gemaakte opnames? Dat bouwt allemaal verder op deze structuur.
