[en]
Windows video fix: the picture now appears.

mpv was being asked to draw inside the app's own window, but Electron keeps a
window of its own in front of it and paints it solid, so the film played behind
a black sheet and only the sound came through. It now opens a window of its
own, the same size and in the same place as the app, and names whatever is
playing.

What's new is written in three languages from this release on; the app shows
whichever one it is set to, and English when it has no other.

[ku]
چاککردنی وەشانی Windows: ڤیدیۆکە ئێستا دەردەکەوێت.

پێشتر mpv داوای لێ دەکرا لەناو پەنجەرەی ئەپەکەدا وێنەکە بکێشێت، بەڵام Electron
پەنجەرەیەکی خۆی لەسەرەوە ڕادەگرێت و بە ڕەنگێکی داپۆشراو دەیکێشێت — بۆیە وێنەکە
لە ژێریدا دەمایەوە و تەنها دەنگ دەبیسترا. ئێستا پەنجەرەی خۆی هەیە، بە هەمان
قەبارە و شوێنی ئەپەکە، و ناوی ئەوەی دەیبینیت پیشان دەدات.

لەم وەشانەوە «چی نوێیە» بە سێ زمان دەنووسرێت؛ ئەپەکە ئەوە پیشان دەدات کە پێی
ڕێکخراوە، و ئینگلیزی کاتێک هی خۆی نەبوو.

[ar]
إصلاح الفيديو على Windows: الصورة تظهر الآن.

كان mpv يُطلب منه الرسم داخل نافذة التطبيق نفسها، لكن Electron يبقي نافذته
أمامها ويطليها بلون معتم، فكان الفيلم يُعرض خلف ستار أسود ولا يصل سوى الصوت.
أصبحت له الآن نافذة خاصة به، بالحجم نفسه وفي المكان نفسه، وتحمل اسم ما يُعرض.

ابتداءً من هذا الإصدار تُكتب «الجديد» بثلاث لغات؛ ويعرض التطبيق اللغة المضبوطة
عليه، والإنجليزية عند عدم توفر غيرها.
