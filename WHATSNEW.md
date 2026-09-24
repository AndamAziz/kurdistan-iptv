[en]
A channel that will not open now says so, instead of playing another one.

On Windows the player was given the whole list of channels at once. When one
would not open it quietly moved on to the next line of that list, so clicking
one channel started a different one, with nothing said about it.

Underneath that was the reason the channel would not open at all: the player's
own way of fetching a stream hands the stream's own list of parts an address
they are not kept under, so every part comes back missing. FFmpeg's way does
not have that fault, and a channel that fails is now given exactly that, at
once. If it still will not open, the player stops and names it.

The channel it used to jump to is now dropped in about a quarter of a second,
before anything of it is seen or heard.

Two older faults were found while this was traced: one mpv shutting down could
take the next one's controls away with it, and the app could ask for a film
through FFmpeg without the player being told it was allowed to - so the ask was
silently ignored.

Live television, films and episodes that already worked are untouched: the
first attempt is exactly what it always was.

[ku]
کەناڵێک کە ناکرێتەوە ئێستا خۆی دەڵێت، لەبری ئەوەی کەناڵێکی تر لێبدات.

لەسەر Windows هەموو لیستی کەناڵەکان پێکەوە درابوو بە پلەیەرەکە. کاتێک یەکێکیان
نەدەکرایەوە، بێدەنگ دەچووە سەر دێڕی دواتری ئەو لیستە — بۆیە کرتە لەسەر
کەناڵێک کەناڵێکی تری دەستپێدەکرد، بەبێ ئەوەی هیچ بوترێت.

لە ژێر ئەوەوە هۆکاری نەکرانەوەی کەناڵەکە خۆی بوو: ئەو ڕێگایەی پلەیەرەکە بۆ
هێنانی ستریم بەکاریدەهێنا، ناونیشانێکی هەڵە دەداتە لیستی بەشەکانی ستریمەکە،
بۆیە هەموو بەشەکان بزر دەبن. ڕێگاکەی FFmpeg ئەو کەموکوڕییەی نییە، و ئێستا
کەناڵێک کە شکست دەهێنێت یەکسەر بەو ڕێگایە هەوڵدەدرێتەوە. ئەگەر هێشتاش
نەکرایەوە، پلەیەرەکە دەوەستێت و ناوی کەناڵەکە دەڵێت.

ئەو کەناڵەی پێشتر بازی بۆ دەکرد، ئێستا لە نزیکەی چارەکە چرکەیەکدا دەبڕدرێت،
پێش ئەوەی هیچی لێ ببینرێت یان ببیسترێت.

دوو کەموکوڕی کۆنتریش لەم گەڕانەدا دەرکەوتن: mpvـێک لە کاتی داخستنیدا دەیتوانی
کۆنتڕۆڵی ئەوی دواتری لەگەڵ خۆی ببات، وە ئەپەکە دەیتوانی داوای فیلمێک بکات بە
ڕێگای FFmpeg بەبێ ئەوەی پلەیەرەکە بزانێت ڕێگەی پێدراوە — بۆیە داواکەکە بێدەنگ
پشتگوێ دەخرا.

کەناڵە زیندووەکان و فیلم و سریاڵەکان کە پێشتر کاریان دەکرد دەستیان لێ نەدراوە:
هەوڵی یەکەم بە تەواوی وەک خۆیەتی.

[ar]
القناة التي لا تُفتح تقول ذلك الآن، بدل أن تشتغل قناة أخرى.

على Windows كانت قائمة القنوات كلها تُسلَّم للمشغّل دفعة واحدة. وحين تعجز قناة
عن الفتح كان ينتقل بصمت إلى السطر التالي، فيبدأ الضغط على قناة تشغيلَ قناة
أخرى دون أن يُقال شيء.

وتحت ذلك كان سبب عجز القناة عن الفتح: طريقة المشغّل في جلب البث تعطي قائمة
أجزاء البث عنوانًا غير الذي تُحفظ تحته، فتعود كل الأجزاء مفقودة. طريقة FFmpeg
لا تحمل هذا العيب، والقناة التي تفشل تُجرَّب بها فورًا. وإن بقيت لا تُفتح،
يتوقف المشغّل ويسمّيها.

القناة التي كان يقفز إليها تُقطع الآن في نحو ربع ثانية، قبل أن يُرى منها أو
يُسمع شيء.

كما ظهر عيبان أقدم أثناء التتبّع: مشغّل يُغلق كان قد يأخذ معه تحكّم الذي يليه،
وكان التطبيق قد يطلب فيلمًا عبر FFmpeg دون أن يُخبَر المشغّل بأن ذلك مسموح —
فيُتجاهل الطلب بصمت.

القنوات المباشرة والأفلام والحلقات التي كانت تعمل لم تُمس: المحاولة الأولى هي
نفسها تمامًا.
