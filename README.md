# Fai Fai Kitchen & Printer

Kitchen WebView, network receipt printer aur background new-order alarm ek hi Android app mein hain.

Kitchen mein ek dafa PIN login karein. App background mein har 10 second new orders check karti hai. New order par alarm aur notification aati hai; order accept/cancel hone par alarm band hota hai.

Fai Fai Juice Kitchen ke liye free Android network thermal-printer app.

## Kya karta hai

- `https://fai-fai-juice.pages.dev/kitchen` app ke andar kholta hai.
- Kitchen ke Accept aur Reprint buttons se ESC/POS receipt bhejta hai.
- Admin ke `Receipt & Printer` page se Printer IP, port, 58/80mm aur auto-print settings leta hai.
- Network/Wi-Fi/LAN thermal printer ko default port `9100` par print bhejta hai.

## Zaroori baat

Android mobile/tablet aur thermal printer ek hi Wi-Fi/LAN network par hon. Printer ko fixed/static IP dena behtar hai. Printer badalne par sirf Admin setting mein IP change karein; APK dobara build karna zaroori nahi.

## Free APK build

1. Is project ke tamam files ek naye GitHub repository mein upload karein.
2. GitHub mein `Actions` tab kholein.
3. `Build Fai Fai Printer APK` workflow kholein.
4. Successful run ke neeche `Artifacts` se `Fai-Fai-Printer-APK` download karein.
5. Downloaded artifact ZIP extract karke `Fai-Fai-Printer.apk` Android tablet/mobile mein install karein.

Android agar warning dikhaye to browser/file manager ke liye `Install unknown apps` allow karna hoga.
