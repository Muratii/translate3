# Çevirmen (İngilizce -> Türkçe canlı çeviri)

## Nasıl açılır
1. Android Studio'yu kur (Koala / 2024.1 veya üstü): https://developer.android.com/studio
2. File > Open > bu `CevirmenApp` klasörünü seç.
3. "Gradle sync" bitene kadar bekle (ilk seferde birkaç dakika sürer, internet gerekir).
   - "Gradle wrapper yok" benzeri bir uyarı çıkarsa Android Studio'nun önerdiği düzeltmeyi kabul et.
4. Telefonda Ayarlar > Geliştirici seçenekleri > USB hata ayıklamayı aç, USB ile bağla.
5. Üstteki yeşil ▶ (Run) düğmesine bas.

## Kullanım
- İlk açılışta çeviri modeli (~30 MB) bir kez iner.
- "Başlat"a dokun, mikrofon iznini ver, İngilizce konuş.
- Üst kutuda duyulan İngilizce, alt kutuda Türkçe çeviri canlı akar.

## Dosyalar
- app/src/main/java/com/example/cevirmen/MainActivity.kt -> tüm uygulama mantığı + arayüz
- app/src/main/AndroidManifest.xml -> izinler (mikrofon, internet)
- app/build.gradle.kts -> bağımlılıklar (Compose, ML Kit Translate)
