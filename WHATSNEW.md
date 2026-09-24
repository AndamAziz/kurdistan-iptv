[en]
The live channels that would not open on Windows now open.

A panel does not always hand over a channel where it was asked for. NAT GEO
is sent on twice - first to https, then to another machine entirely, the one
that actually carries the stream. Only then does the list of the stream's
parts come back, and those parts hang off the address the list came from.

The player on Windows fetches over its own web code, which follows the moves
but never passes the final address on. So it looked for every part back at
the panel, where they are not, and the channel died - and then, being handed
the whole queue, quietly started a different channel instead.

Channels now come through the app's own go-between, the same one the rest of
the app already used. It follows the moves itself and writes every address in
the stream's list out in full, so there is nothing left for the player to
resolve and nowhere left for it to look wrongly. The stream itself still
comes straight from wherever it lives; only the list passes through.

If the go-between is not running, or a channel will not come that way, the
old path is still underneath, unchanged. Films and episodes are untouched.

[ku]
ئەو کەناڵە زیندووانەی لەسەر Windows نەدەکرانەوە، ئێستا دەکرێنەوە.

پانێڵەکە هەمیشە کەناڵەکە لەو شوێنەدا نادات کە داوا کراوە. NAT GEO دوو جار
دەگوازرێتەوە — یەکەم بۆ https، دواتر بۆ ئامێرێکی تەواو جیاواز، ئەوەی بە
ڕاستی ستریمەکەی هەڵگرتووە. تەنها ئەوکات لیستی پارچەکانی ستریمەکە دێتەوە، و
ئەو پارچانە سەر بەو ناونیشانەن کە لیستەکە لێیەوە هات.

پلەیەرەکە لەسەر Windows بە کۆدی وێبی خۆی دەیهێنێت، کە بەدوای گواستنەوەکاندا
دەچێت بەڵام ناونیشانی کۆتایی ناگەیەنێت. بۆیە بەدوای هەموو پارچەیەکدا لە
پانێڵەکە دەگەڕا، کە لەوێ نین، و کەناڵەکە دەمرد — دواتر، چونکە هەموو لیستەکەی
پێدرابوو، بێدەنگ کەناڵێکی تری دەستپێدەکرد.

ئێستا کەناڵەکان بەناو ناوبژیوانی خودی ئەپەکەدا دێن، هەمان ئەوەی پێشتریش
بەکاردەهات. خۆی بەدوای گواستنەوەکاندا دەچێت و هەموو ناونیشانێکی ناو لیستی
ستریمەکە بە تەواوی دەنووسێت، بۆیە هیچ نامێنێتەوە بۆ پلەیەرەکە کە
دەربخات، و هیچ شوێنێک نامێنێت کە بە هەڵە لێی بگەڕێت. خودی ستریمەکە هەر
ڕاستەوخۆ لەو شوێنەوە دێت کە لێیەتی؛ تەنها لیستەکە تێدەپەڕێت.

ئەگەر ناوبژیوانەکە کار نەکات، یان کەناڵێک بەو ڕێگایە نەیەت، ڕێگا کۆنەکە
هەر لە ژێرەوەیە، بێ گۆڕان. فیلم و سریاڵ دەستیان لێ نەدراوە.

[ar]
القنوات المباشرة التي كانت لا تُفتح على Windows صارت تُفتح.

المنصّة لا تسلّم القناة دائمًا حيث طُلبت. NAT GEO تُحوَّل مرّتين: أولًا إلى
https، ثم إلى جهاز آخر تمامًا، الجهاز الذي يحمل البث فعلًا. عندها فقط تعود
قائمة أجزاء البث، وتلك الأجزاء تتبع العنوان الذي جاءت منه القائمة.

المشغّل على Windows يجلب عبر شيفرته الخاصة، فيتبع التحويلات لكنه لا ينقل
العنوان النهائي. فكان يبحث عن كل جزء عند المنصّة، حيث لا وجود لها، فتموت
القناة — ثم، وقد سُلّمت له القائمة كلها، يبدأ بصمت قناة أخرى.

صارت القنوات تمرّ عبر وسيط التطبيق نفسه، ذاته الذي كان يستعمله أصلًا. يتبع
التحويلات بنفسه ويكتب كل عنوان في قائمة البث كاملًا، فلا يبقى للمشغّل ما
يستنتجه ولا مكان يبحث فيه خطأً. البث نفسه ما زال يأتي مباشرة من موضعه؛
القائمة وحدها هي التي تمرّ.

وإن لم يكن الوسيط يعمل، أو لم تأتِ قناة بتلك الطريقة، فالطريق القديم ما زال
تحتها دون تغيير. الأفلام والحلقات لم تُمس.
