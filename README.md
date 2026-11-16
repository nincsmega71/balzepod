# BlazePod Vezérlő (Android)

Saját Android app az eredeti BlazePod bólyákhoz, a gyári app és előfizetés nélkül.

## Funkciók
- **Bólyák fül:** beállítod a bólyák számát (1–12), majd a *Keresés és csatlakozás* gomb a legerősebb jelű bólyákhoz csatlakozik. Sorszámot kapnak (#1, #2…), a *Villant* gombbal egyenként azonosíthatók.
- **Edzés fül:** a bólyák véletlenszerűen villannak fel a megadott ideig.
  - időtartam (15 s – 30 perc),
  - szünet két felvillanás között,
  - egy felvillanás leghosszabb ideje (vagy amíg meg nem érinted),
  - szín vagy véletlen szín,
  - 3-2-1 visszaszámlálás, hangjelzés,
  - eredmény: találat, kihagyott, téves érintés, utolsó/átlag/legjobb/leglassabb reakcióidő.
  A reakcióidőt a bólya saját időzítője méri, így nincs benne Bluetooth-késés.
- **Teszt fül:**
  - *Érintésteszt:* minden bólya világít, és érintésre kialszik. Az app zölddel jelzi, melyik működik.
  - *Azonosítás:* a bólyák sorban felvillannak.
  - *Színkör:* minden bólya végigjátssza a színeket.

## Fordítás Android Studióban
1. Csomagold ki a zipet, majd az Android Studióban válaszd a **File → Open** menüt, és nyisd meg a `BlazePodVezerlo` mappát.
2. Várd meg a Gradle Sync végét. Az első alkalommal letölti a Gradle 8.11.1-et és a függőségeket.
   - Ha a Studio jelzi, hogy hiányzik a `gradlew`, fogadd el a javaslatát, vagy futtasd a Gradle panelen a *Tasks → build setup → wrapper* feladatot.
3. Csatlakoztasd a telefont USB-n (USB-hibakeresés legyen bekapcsolva), majd nyomd meg a ▶ **Run** gombot.
4. Ha APK fájlt szeretnél: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. Az APK helye: `app/build/outputs/apk/debug/app-debug.apk`.

## Használat
1. Kapcsold be a bólyákat (egy érintés a tetejükre).
2. Az app első indításakor engedélyezd a „Közeli eszközök” hozzáférést.
3. A **Bólyák** fülön állítsd be a darabszámot, majd nyomd meg a *Keresés és csatlakozás* gombot. A csatlakozott bólyák kettőt villannak zölden.
4. A gyári appot közben zárd be, mert egy bólya egyszerre csak egy telefonhoz kapcsolódhat.

## Megjegyzés
- A legtöbb telefon egyszerre kb. 7–10 BLE eszközt tud kezelni.
- A protokoll forrása a [sasodoma/blazepod-hacking](https://github.com/sasodoma/blazepod-hacking) projekt visszafejtése (GPL-3.0). Ennek megfelelően ez a projekt is GPL-3.0 licencű.
