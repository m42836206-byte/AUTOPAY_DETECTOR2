# Autopay Detector (Android)

SMS inbox padhkar autopay / mandate / subscription pakadta hai. Optional: bank statement CSV bhi chun sakte ho.
App mein INTERNET permission nahi hai, data phone se bahar nahi jaata.

## APK banane ke 2 tareeke
1. Android Studio: is folder ko open karo, Build > Build APK(s). APK: app/build/outputs/apk/debug/app-debug.apk
2. GitHub (bina Android Studio): naya repo banao, ye saari files upload karo, Actions tab > "Build APK" > Run workflow. Khatam hone par "autopay-apk" artifact download karo.

## Phone mein install
APK kholo, "Install unknown apps" allow karo, install karo. App kholo > "SMS scan karo" > SMS permission allow karo.

Android 13+ par agar permission grey ho: Settings > Apps > Autopay Detector > teen dot (⋮) > "Allow restricted settings", phir SMS permission do.

## Dhyan do
- Sirf Android. iPhone app ko SMS padhne nahi deta.
- Ye personal use ke liye hai. Play Store par READ_SMS wale app ke liye Google ki alag approval lagti hai.
- Pattern detection hai, 100% pakka nahi. Final check UPI app ke Autopay section ya bank ke mandate list mein karo.
