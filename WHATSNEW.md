[en]
Films now play in the Google Play version and on Windows, as they already
did in the version from GitHub.

Some film servers send a certificate that is not quite in order: the
certificate in the middle is missing, or it has run out. The Google Play
version now does what a browser does when the normal check fails: it fetches
the missing certificate and checks the chain again, and it forgives a
certificate that has run out only if everything else about it is right.
Live channels and every server that was already working are not touched.

On Windows, such a server is now asked again at once without the strict
check, as VLC does, even on networks that block secure DNS. The server test
in Settings now opens streams exactly the way the Windows player does, and
no longer says H.265 is unsupported on Windows.

[ku]
فیلمەکان ئێستا لە وەشانی گووگڵ پلەی و ویندۆزیش کاردەکەن، وەک چۆن پێشتر
لە وەشانی GitHub کاریان دەکرد.

هەندێ سێرڤەری فیلم بڕوانامەیەک دەنێرن کە تەواو ڕێک نییە: بڕوانامەی
ناوەڕاست ونە، یان کاتی بەسەرچووە. وەشانی گووگڵ پلەی ئێستا وەک وێبگەڕ
دەکات کاتێک پشکنینی ئاسایی سەرناکەوێت: بڕوانامە ونەکە دەهێنێت و زنجیرەکە
دووبارە دەپشکنێت، و بڕوانامەی بەسەرچوو تەنها ئەو کاتە قبووڵ دەکات کە
هەموو شتێکی تری دروست بێت. کەناڵە ڕاستەوخۆکان و ئەو سێرڤەرانەی پێشتر
کاریان دەکرد دەستیان لێنەدراوە.

لە ویندۆز، ئەو جۆرە سێرڤەرانە یەکسەر بەبێ پشکنینی توند دووبارە
دەپرسرێنەوە، وەک VLC، تەنانەت لەو تۆڕانەشی DNSی پارێزراو دادەخەن. تاقیکردنەوەی
سێرڤەر لە ڕێکخستنەکان ئێستا وەک پلەیەری ویندۆز ستریمەکان دەکاتەوە، و
چیتر نالێت H.265 لە ویندۆز پشتگیری ناکرێت.

[ar]
الأفلام تعمل الآن في نسخة Google Play وعلى ويندوز، كما كانت تعمل في نسخة
GitHub.

بعض خوادم الأفلام ترسل شهادة غير سليمة تماماً: الشهادة الوسيطة مفقودة، أو
انتهت صلاحيتها. نسخة Google Play تفعل الآن ما يفعله المتصفح عندما يفشل
الفحص العادي: تجلب الشهادة المفقودة وتعيد فحص السلسلة، وتقبل الشهادة
المنتهية فقط إذا كان كل ما عداها صحيحاً. القنوات المباشرة وكل خادم كان يعمل
لم يتغير.

على ويندوز، يُسأل هذا النوع من الخوادم مرة أخرى فوراً دون الفحص الصارم، كما
يفعل VLC، حتى على الشبكات التي تحجب DNS الآمن. وفحص الخادم في الإعدادات
يفتح البث الآن بالطريقة نفسها التي يفتحه بها مشغّل ويندوز، ولم يعد يقول إن
H.265 غير مدعوم على ويندوز.
