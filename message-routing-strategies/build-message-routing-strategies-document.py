import re

import pptx
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN

HEBREW = re.compile(r"[֐-׿]")

COLOR_NAVY = RGBColor(20, 35, 60)
COLOR_BLUE = RGBColor(0, 122, 255)
COLOR_DARK = RGBColor(40, 40, 40)
COLOR_WHITE = RGBColor(255, 255, 255)
COLOR_GRAY = RGBColor(238, 241, 245)
COLOR_ACCENT = RGBColor(190, 85, 15)    # ענבר – המילה שאסור לפספס
COLOR_ALERT = RGBColor(197, 48, 48)     # אדום – נקודות כשל
COLOR_OK = RGBColor(28, 120, 62)


def mark_rtl(paragraph) -> None:
    """PowerPoint needs pPr/@rtl to actually flip direction; alignment alone only
    moves the line to the right and leaves punctuation/bullets on the wrong side."""
    pPr = paragraph._p.get_or_add_pPr()
    pPr.set("rtl", "1")
    if paragraph.alignment is None:
        paragraph.alignment = PP_ALIGN.RIGHT


def apply_rtl(prs) -> None:
    """Mark every Hebrew-containing paragraph in the deck (shapes, table cells,
    speaker notes) as right-to-left."""
    def frames(shape):
        if shape.has_text_frame:
            yield shape.text_frame
        if getattr(shape, "has_table", False):
            for row in shape.table.rows:
                for cell in row.cells:
                    yield cell.text_frame

    for slide in prs.slides:
        targets = list(slide.shapes)
        if slide.has_notes_slide:
            targets += list(slide.notes_slide.shapes)
        for shape in targets:
            for tf in frames(shape):
                for p in tf.paragraphs:
                    if HEBREW.search(p.text):
                        mark_rtl(p)


def create_routing_strategies_presentation():
    prs = Presentation()

    # יחס מסך 16:9
    prs.slide_width = Inches(13.333)
    prs.slide_height = Inches(7.5)

    blank_layout = prs.slide_layouts[6]  # שקף ריק לעיצוב מותאם

    def add_slide(title_text):
        slide = prs.slides.add_slide(blank_layout)
        txBox = slide.shapes.add_textbox(Inches(0.7), Inches(0.4), Inches(11.933), Inches(0.9))
        p = txBox.text_frame.paragraphs[0]
        p.text = title_text
        p.font.size = Pt(28)
        p.font.bold = True
        p.font.color.rgb = COLOR_NAVY
        p.alignment = PP_ALIGN.RIGHT
        return slide

    def add_bullets(slide, items, size=18, top=1.5, height=5.5):
        """items: (lead, body) — הפתיח מודגש בכחול, ההמשך רגיל, שניהם באותה פסקה."""
        tf = slide.shapes.add_textbox(Inches(0.7), Inches(top), Inches(11.933), Inches(height)).text_frame
        tf.word_wrap = True
        for idx, (lead, body) in enumerate(items):
            p = tf.paragraphs[0] if idx == 0 else tf.add_paragraph()
            p.space_after = Pt(12)
            p.alignment = PP_ALIGN.RIGHT
            r1 = p.add_run()
            r1.text = f"• {lead} "
            r1.font.size = Pt(size)
            r1.font.bold = True
            r1.font.color.rgb = COLOR_BLUE
            # «…» מסמן את המילה שאסור לפספס. לא כוכביות: התו * מופיע בתוכן עצמו
            for part in re.split(r"«(.+?)»", body):
                if not part:
                    continue
                r = p.add_run()
                r.text = part
                r.font.size = Pt(size)
                highlighted = f"«{part}»" in body
                r.font.bold = highlighted
                r.font.color.rgb = COLOR_ACCENT if highlighted else COLOR_DARK
        return tf

    def add_table(slide, headers, rows, top=1.5, height=4.5, size=15):
        table = slide.shapes.add_table(
            len(rows) + 1, len(headers), Inches(0.7), Inches(top), Inches(11.933), Inches(height)
        ).table
        for i, h in enumerate(headers):
            cell = table.cell(0, i)
            cell.text = h
            cell.fill.solid()
            cell.fill.fore_color.rgb = COLOR_NAVY
            for p in cell.text_frame.paragraphs:
                p.font.size = Pt(size)
                p.font.bold = True
                p.font.color.rgb = COLOR_WHITE
                p.alignment = PP_ALIGN.RIGHT
        for r, row in enumerate(rows, start=1):
            for c, value in enumerate(row):
                cell = table.cell(r, c)
                cell.text = value
                for p in cell.text_frame.paragraphs:
                    p.font.size = Pt(size)
                    p.font.color.rgb = COLOR_DARK
                    p.alignment = PP_ALIGN.RIGHT
        return table

    def add_caption(slide, text, size=15, top=6.3):
        tf = slide.shapes.add_textbox(Inches(0.7), Inches(top), Inches(11.933), Inches(0.9)).text_frame
        tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = text
        p.font.size = Pt(size)
        p.font.italic = True
        p.font.color.rgb = COLOR_BLUE
        p.alignment = PP_ALIGN.RIGHT

    def notes(slide, text):
        slide.notes_slide.notes_text_frame.text = text

    def box(slide, x, y, w, h, title, sub=None, fill=COLOR_GRAY, fg=COLOR_NAVY, size=13, line=None):
        from pptx.enum.shapes import MSO_SHAPE
        sh = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(h))
        sh.fill.solid()
        sh.fill.fore_color.rgb = fill
        sh.line.color.rgb = line or COLOR_NAVY
        sh.line.width = Pt(1)
        tf = sh.text_frame
        tf.word_wrap = True
        tf.margin_left = tf.margin_right = Inches(0.05)
        tf.margin_top = tf.margin_bottom = Inches(0.02)
        p = tf.paragraphs[0]
        p.text = title
        p.font.size = Pt(size)
        p.font.bold = True
        p.font.color.rgb = fg
        p.alignment = PP_ALIGN.CENTER
        if sub:
            p2 = tf.add_paragraph()
            p2.text = sub
            p2.font.size = Pt(size - 2)
            p2.font.color.rgb = fg
            p2.alignment = PP_ALIGN.CENTER
        return sh

    def arrow(slide, x, y, w=0.85, h=0.28, color=None):
        """חץ שמאלה – הזרימה בדיאגרמה היא מימין לשמאל, ככיוון הקריאה."""
        from pptx.enum.shapes import MSO_SHAPE
        sh = slide.shapes.add_shape(MSO_SHAPE.LEFT_ARROW, Inches(x), Inches(y), Inches(w), Inches(h))
        sh.fill.solid()
        sh.fill.fore_color.rgb = color or COLOR_BLUE
        sh.line.fill.background()
        return sh

    def rule(slide, x, y, w, h):
        from pptx.enum.shapes import MSO_SHAPE
        sh = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(h))
        sh.fill.solid()
        sh.fill.fore_color.rgb = COLOR_BLUE
        sh.line.fill.background()
        return sh

    def side_note(slide, y, text):
        tf = slide.shapes.add_textbox(Inches(0.8), Inches(y), Inches(3.3), Inches(1.0)).text_frame
        tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = text
        p.font.size = Pt(12)
        p.font.color.rgb = COLOR_DARK
        p.alignment = PP_ALIGN.RIGHT

    # -------------------------------------------------------------------------
    # 1. שער  (~20 שניות)
    # -------------------------------------------------------------------------
    slide = prs.slides.add_slide(blank_layout)
    bg = slide.shapes.add_shape(pptx.enum.shapes.MSO_SHAPE.RECTANGLE, 0, 0, prs.slide_width, prs.slide_height)
    bg.fill.solid()
    bg.fill.fore_color.rgb = COLOR_NAVY
    bg.line.fill.background()

    tf = slide.shapes.add_textbox(Inches(1.5), Inches(2.6), Inches(10.333), Inches(2.5)).text_frame
    tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "Who Gets the Copy?"
    p.font.size = Pt(48)
    p.font.bold = True
    p.font.color.rgb = COLOR_WHITE
    p.alignment = PP_ALIGN.CENTER

    p2 = tf.add_paragraph()
    p2.text = "Message Routing Strategies, told through one Black Friday order"
    p2.font.size = Pt(22)
    p2.font.color.rgb = COLOR_BLUE
    p2.alignment = PP_ALIGN.CENTER

    p3 = tf.add_paragraph()
    p3.text = "איך הברוקר מחליט לאן הודעה הולכת – וכמה זה עולה לנו"
    p3.font.size = Pt(18)
    p3.font.color.rgb = COLOR_WHITE
    p3.alignment = PP_ALIGN.CENTER

    notes(slide,
        "Abstract: A customer clicks \"Confirm Order\" on Black Friday, and five systems have "
        "to react. This talk follows that one order through the message broker to see how three "
        "routing strategies - direct, fanout, and topic - each answer the question of who gets "
        "a copy, and what each one costs. Then we break the same order on purpose: a slow "
        "subscriber that starves healthy ones, a message that fails forever, and a worker that "
        "dies mid-charge. You'll leave knowing how to pick a strategy, and why idempotent "
        "consumers are not optional.\n\n"
        "10 דקות. השם הוא שאלה – ולכן כדאי לפתוח בה ולסגור אותה בשקף הסיכום: "
        "מי מקבל עותק, ומי מחליט."
    )

    # -------------------------------------------------------------------------
    # 2. הבעיה  (~1 דקה)
    # -------------------------------------------------------------------------
    slide = add_slide("הבעיה: למה בכלל צריך ניתוב?")

    tf = slide.shapes.add_textbox(Inches(0.7), Inches(1.34), Inches(11.933), Inches(0.6)).text_frame
    tf.word_wrap = True
    p = tf.paragraphs[0]
    r = p.add_run()
    r.text = "לקוח לוחץ 'אישור הזמנה'. "
    r.font.size = Pt(16); r.font.bold = True; r.font.color.rgb = COLOR_BLUE
    r = p.add_run()
    r.text = ("ארבע מערכות צריכות להגיב – חיוב, מלאי, מייל ואנליטיקה. "
              "השאלה היחידה היא מי אחראי לדעת מי הן.")
    r.font.size = Pt(16); r.font.color.rgb = COLOR_DARK
    p.alignment = PP_ALIGN.RIGHT

    def panel_title(x, w, text, color):
        t = slide.shapes.add_textbox(Inches(x), Inches(2.05), Inches(w), Inches(0.32)).text_frame
        q = t.paragraphs[0]
        q.text = text
        q.font.size = Pt(13); q.font.bold = True; q.font.color.rgb = color
        q.alignment = PP_ALIGN.CENTER

    def panel_note(x, w, text, color):
        t = slide.shapes.add_textbox(Inches(x), Inches(4.85), Inches(w), Inches(0.7)).text_frame
        t.word_wrap = True
        q = t.paragraphs[0]
        q.text = text
        q.font.size = Pt(12); q.font.bold = True; q.font.color.rgb = color
        q.alignment = PP_ALIGN.CENTER

    RED_FILL = RGBColor(253, 234, 234)
    GREEN_FILL = RGBColor(233, 245, 236)
    TARGETS = ["חיוב", "מלאי", "מייל", "אנליטיקה"]
    ROWS = [2.50, 3.10, 3.70, 4.30]

    # ----- מימין: קריאות ישירות. ארבע כתובות בקוד של השולח -----
    panel_title(7.1, 5.9, "בלי ברוקר: השולח מכיר כל נמען", COLOR_ALERT)
    box(slide, 11.4, 3.15, 1.6, 1.00, "שירות ההזמנות", "מחזיק 4 כתובות", size=12,
        fill=RED_FILL, fg=COLOR_ALERT, line=COLOR_ALERT)
    for y, name in zip(ROWS, TARGETS):
        box(slide, 7.6, y, 1.9, 0.50, name, size=12)
        arrow(slide, 9.60, y + 0.14, w=1.70, h=0.22, color=COLOR_ALERT)
    panel_note(7.1, 5.9,
               "נמען חמישי = שינוי קוד בשירות ההזמנות, בדיקות ופריסה מחדש", COLOR_ALERT)

    rule(slide, 6.85, 2.10, 0.02, 2.60)

    # ----- משמאל: ברוקר. כתובת אחת -----
    panel_title(0.7, 5.9, "עם ברוקר: השולח מכיר כתובת אחת", COLOR_OK)
    box(slide, 5.0, 3.15, 1.6, 1.00, "שירות ההזמנות", "מפרסם עובדה אחת", size=12,
        fill=GREEN_FILL, fg=COLOR_OK, line=COLOR_OK)
    arrow(slide, 4.35, 3.54, w=0.60, h=0.22, color=COLOR_OK)
    box(slide, 2.75, 3.15, 1.6, 1.00, "Message Broker", "הוא שמנתב", size=12,
        fill=COLOR_NAVY, fg=COLOR_WHITE)
    for y, name in zip(ROWS, TARGETS):
        box(slide, 0.7, y, 1.5, 0.50, name, size=12)
        arrow(slide, 2.20, y + 0.14, w=0.50, h=0.22, color=COLOR_OK)
    panel_note(0.7, 5.9,
               "נמען חמישי = חוק חדש בברוקר. השולח לא נגע, לא נבדק, לא נפרס", COLOR_OK)

    tf = slide.shapes.add_textbox(Inches(0.7), Inches(5.62), Inches(11.933), Inches(1.1)).text_frame
    tf.word_wrap = True
    for idx, (lead, body) in enumerate([
        ("מה שבאמת השתנה:",
         " ההחלטה מי מקבל עותק עברה מהקוד של השולח אל התשתית. זה מוריד את הצימוד (Coupling) – "
         "המידה שבה שינוי בשירות אחד מכריח שינוי בשירות אחר."),
        ("ולמה זה מגיע לשקף הראשון:",
         " ברגע שהברוקר מחליט, צריך להגיד לו איך – וזו בדיוק אסטרטגיית הניתוב. "
         "היא קובעת את «הצימוד», את «התפוקה» ואת «סדר ההודעות»: "
         "שלושה דברים שקשה לשנות בדיעבד."),
    ]):
        p = tf.paragraphs[0] if idx == 0 else tf.add_paragraph()
        p.alignment = PP_ALIGN.RIGHT
        r = p.add_run()
        r.text = lead
        r.font.size = Pt(13); r.font.bold = True; r.font.color.rgb = COLOR_BLUE
        for part in re.split(r"«(.+?)»", body):
            if not part:
                continue
            r = p.add_run()
            r.text = part
            r.font.size = Pt(13)
            hot = f"«{part}»" in body
            r.font.bold = hot
            r.font.color.rgb = COLOR_ACCENT if hot else COLOR_DARK

    notes(slide,
        "הפער שכדאי להצביע עליו: מימין ארבעה חוטים יוצאים מהשולח, משמאל אחד. "
        "זה כל הרעיון של ברוקר במשפט אחד.\n"
        "אם שואלים 'ומה אם הברוקר נופל?' – זו נקודת כשל יחידה שמטפלים בה בשכפול, "
        "ובתמורה מקבלים שכל שאר השירותים מנותקים זה מזה."
    )

    # -------------------------------------------------------------------------
    # 3. איפה מתבצע הניתוב  (~45 שניות)
    # -------------------------------------------------------------------------
    slide = add_slide("איפה מתבצע הניתוב?")
    add_bullets(slide, [
        ("בצד הברוקר (Broker-Side):",
         "הברוקר מחזיק את חוקי הניתוב ומעביר לכל תור רק את מה שרלוונטי לו. זה המודל שבו "
         "נתמקד, והמונחים שנשתמש בהם – «Exchange ו-Binding» – הם המילון שלו: המילון של AMQP, "
         "התקן שמאחורי RabbitMQ. בטכנולוגיות אחרות אותן אסטרטגיות קיימות, אך במקום אחר "
         "ובשמות אחרים."),
        ("בצד הצרכן (Consumer-Side Filtering):",
         "הברוקר שולח לכל צרכן את כל ההודעות, וכל אחד בודק בעצמו מה שלו. אם רק אחת מעשר "
         "מעניינת אותו – «90% מהתעבורה חצתה את הרשת לחינם», והצרכן שרף CPU על פענוח כל "
         "הודעה רק כדי לזרוק אותה."),
        ("ההבדל בשורה אחת:",
         "ניתוב בברוקר – הרשת נושאת «רק את מה שצריך». סינון בצרכן – הרשת נושאת הכול, "
         "והצרכן משלם על מה שלא ביקש."),
    ], size=20)
    notes(slide,
        "שקף התמצאות מהיר. אם מישהו שואל על טכנולוגיות אחרות – לומר שניתוב בצד השרת אינו "
        "מודל אוניברסלי, ולהמשיך."
    )

    # -------------------------------------------------------------------------
    # 4. שלוש האסטרטגיות  (~45 שניות)
    # -------------------------------------------------------------------------
    # -------------------------------------------------------------------------
    # 5. Direct  (~1 דקה)
    # -------------------------------------------------------------------------
    slide = add_slide("1. Direct Routing – ניתוב ישיר")
    add_bullets(slide, [
        ("מנגנון:",
         "לכל הודעה יש Routing Key – מחרוזת קצרה במעטפה, למשל orders.charge. הברוקר מחזיק "
         "טבלה שממפה מחרוזת ← תורים, ומחפש בה את המחרוזת המדויקת."),
        ("למה 'טבלת hash' חשוב:",
         "זהו חיפוש בטבלה, ולכן «זמן החיפוש לא גדל עם מספר התורים» – אלף תורים עולים כמו "
         "תור אחד. זהו מסלול הניתוב הזול ביותר שקיים."),
        ("מתי Direct – פקודה, לא אירוע:",
         "'חייב את הכרטיס' היא פקודה: יש לה בעל אחד, ואסור ששניים יבצעו אותה. 'הזמנה בוצעה' "
         "הוא אירוע – עובדה שקרתה, שמעניינת רבים. «פקודה ← Direct, אירוע ← Fanout או Topic»."),
        ("הרחבה – Competing Consumers:",
         "Worker הוא מופע של הקוד שלכם שרץ על שרת, מושך מהתור ומבצע. מחברים עשרה כאלה לאותו "
         "תור, והברוקר מחלק ביניהם – «כל הודעה נמסרת ל-Worker אחד בלבד»."),
    ], size=18)
    notes(slide,
        "להדגיש שה-Worker אינו קשור ללקוח מסוים – הוא סתם זוג ידיים פנויות. "
        "זו ההנחה שמתפוצצת בשקף הבא."
    )

    # -------------------------------------------------------------------------
    # הסדר שנשבר, והפיצול לפי מפתח
    # -------------------------------------------------------------------------
    slide = add_slide("המחיר של עשרה Workers: הסדר נשבר")

    tf = slide.shapes.add_textbox(Inches(0.7), Inches(1.32), Inches(11.933), Inches(0.82)).text_frame
    tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = ("דלפק אחד, עשרה פקידים, תור אחד. מגיעים שני פתקים על אותו לקוח – 'קח ממנו 100 ₪' "
              "ואחריו 'החזר לו 100 ₪' – וכל פתק נלקח בידי פקיד אחר. שניהם עובדים באותו רגע.")
    p.font.size = Pt(15)
    p.font.color.rgb = COLOR_DARK
    p.alignment = PP_ALIGN.RIGHT

    def panel_title(x, w, text, color):
        t = slide.shapes.add_textbox(Inches(x), Inches(2.22), Inches(w), Inches(0.32)).text_frame
        q = t.paragraphs[0]
        q.text = text
        q.font.size = Pt(13)
        q.font.bold = True
        q.font.color.rgb = color
        q.alignment = PP_ALIGN.CENTER

    def panel_note(x, w, y, text, color):
        t = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(0.6)).text_frame
        t.word_wrap = True
        q = t.paragraphs[0]
        q.text = text
        q.font.size = Pt(12)
        q.font.bold = True
        q.font.color.rgb = color
        q.alignment = PP_ALIGN.CENTER

    RED_FILL = RGBColor(253, 234, 234)
    GREEN_FILL = RGBColor(233, 245, 236)

    # ----- מימין: הבעיה. תור אחד, שני Workers מושכים ממנו בו-זמנית -----
    panel_title(7.1, 5.9, "הבעיה: תור אחד, כמה Workers", COLOR_ALERT)
    box(slide, 11.4, 3.05, 1.6, 0.90, "לקוח #8123", "חייב, ואז זכה", size=12)
    arrow(slide, 10.85, 3.39, w=0.50, h=0.22)
    box(slide, 9.25, 3.05, 1.6, 0.90, "charge-queue", "כל הלקוחות יחד", size=12)
    for y, w_name, job in [(2.90, "Worker A", "לוקח: חייב"), (3.75, "Worker B", "לוקח: זכה")]:
        box(slide, 7.1, y, 1.6, 0.65, w_name, job, fill=RED_FILL, fg=COLOR_ALERT,
            line=COLOR_ALERT, size=12)
        arrow(slide, 8.70, y + 0.21, w=0.50, h=0.22)
    panel_note(7.1, 5.9, 4.62,
               "שניהם עובדים באותו רגע – הזיכוי עלול להסתיים לפני החיוב", COLOR_ALERT)

    rule(slide, 6.85, 2.25, 0.02, 2.90)

    # ----- משמאל: הפתרון. לכל לקוח תור משלו, ולכל תור Worker אחד -----
    panel_title(0.7, 5.9, "הפתרון: פיצול לפי מזהה הלקוח", COLOR_OK)
    for y, cust, h, queue, msgs, worker in [
        (2.90, "לקוח #8123", "hash ← 1", "charge-queue-1", "חייב ← זכה", "Worker A"),
        (3.75, "לקוח #4471", "hash ← 2", "charge-queue-2", "הזמנה ← חיוב", "Worker B"),
    ]:
        box(slide, 5.0, y, 1.6, 0.65, cust, h, size=12)
        arrow(slide, 4.45, y + 0.21, w=0.50, h=0.22)
        box(slide, 2.85, y, 1.6, 0.65, queue, msgs, fill=GREEN_FILL, fg=COLOR_OK,
            line=COLOR_OK, size=11)
        arrow(slide, 2.30, y + 0.21, w=0.50, h=0.22)
        box(slide, 0.7, y, 1.6, 0.65, worker, "Worker יחיד", fill=GREEN_FILL, fg=COLOR_OK,
            line=COLOR_OK, size=12)
    panel_note(0.7, 5.9, 4.62,
               "אותו לקוח – תמיד אותו תור, ובו Worker אחד בלבד", COLOR_OK)

    # ----- ההסבר: שני התנאים שיוצרים יחד את שמירת הסדר -----
    tf = slide.shapes.add_textbox(Inches(0.7), Inches(5.35), Inches(11.933), Inches(1.4)).text_frame
    tf.word_wrap = True
    for idx, (lead, body) in enumerate([
        ("למה הסדר נשמר – שני תנאים, ורק יחד:", ""),
        ("1. אותו מזהה ← תמיד אותו תור.",
         " המפתח כאן הוא מזהה הלקוח – המספר הייחודי שלו במסד, למשל 8123. hash של אותו מספר "
         "מחזיר תמיד את אותה תוצאה, ולכן כל הודעה שלו נוחתת ב-charge-queue-1. לעולם לא באחר."),
        ("2. לתור הזה יש Worker אחד.",
         " תור הוא FIFO – הנכנס ראשון יוצא ראשון. עם צרכן יחיד אין מקביליות בתוכו, "
         "ולכן 'חייב' מטופל עד הסוף לפני ש'זכה' בכלל מתחיל."),
    ]):
        p = tf.paragraphs[0] if idx == 0 else tf.add_paragraph()
        p.alignment = PP_ALIGN.RIGHT
        r = p.add_run()
        r.text = lead
        r.font.size = Pt(13)
        r.font.bold = True
        r.font.color.rgb = COLOR_NAVY if idx == 0 else COLOR_BLUE
        if body:
            r2 = p.add_run()
            r2.text = body
            r2.font.size = Pt(13)
            r2.font.color.rgb = COLOR_DARK

    notes(slide,
        "אנלוגיית הדלפק קודם, ורק אז הדיאגרמה. הנקודה: אף פקיד לא טעה – פשוט אף אחד לא היה "
        "אחראי על הסדר.\n"
        "שני התנאים בתחתית הם העיקר, ואף אחד מהם לא מספיק לבדו: פיצול בלי Worker יחיד לכל תור "
        "מחזיר את הבעיה, ו-Worker יחיד בלי פיצול מוותר על המקביליות לגמרי.\n"
        "המקביליות לא אבדה – היא עברה מלהיות בין הודעות של אותו לקוח, לבין לקוחות שונים."
    )

    # -------------------------------------------------------------------------
    # 6. Fanout  (~1.5 דקות)
    # -------------------------------------------------------------------------
    slide = add_slide("2. Fanout Routing – שידור לכולם")
    add_bullets(slide, [
        ("מנגנון:",
         "הברוקר מתעלם מהמפתח ומעתיק את ההודעה לכל תור שמחובר ל-Exchange. "
         "«'מנוי' = תור + השירות שקורא ממנו»: התור מחזיק את ההודעות, ה-Consumer הוא הקוד "
         "שמעבד אותן, וה-Binding הוא החיבור ביניהם."),
        ("המחיר – עלות ליניארית במספר המנויים:",
         "ה-Exchange אינו מחזיק הודעות, הוא רק מנתב. לכן כדי להגיע לעשרה תורים הברוקר מבצע "
         "«עשר פעולות הכנסה נפרדות», ובהמשך עשר מסירות נפרדות ברשת – אחת לכל צרכן."),
    ], size=17, top=1.4, height=2.0)

    add_table(slide,
        ["מסירות ברשת", "פעולות הכנסה לתור", "התרחיש"],
        [
            ["1", "1", "מנוי אחד, הודעה אחת"],
            ["10", "10", "עשרה מנויים, אותה הודעה אחת"],
            ["10,000 בשנייה", "10,000 בשנייה", "עשרה מנויים, 1,000 הודעות בשנייה"],
        ],
        top=3.55, height=1.85, size=15,
    )
    add_caption(slide,
        "היתרון בתמורה: מנוי חדש הוא Binding נוסף – שינוי קונפיגורציה, בלי לגעת בשולח "
        "ובלי לגעת בשאר המנויים.",
        top=5.6,
    )
    notes(slide,
        "דיוק למי ששואל: 'עשר פעולות' אינן בהכרח עשר כתיבות של גוף ההודעה לדיסק – RabbitMQ "
        "שומר את הגוף פעם אחת ומחזיק הפניה בכל תור. מה שכן ליניארי בוודאות: ניהול התור לכל "
        "מנוי, והמסירה ברשת לכל צרכן בנפרד."
    )

    # -------------------------------------------------------------------------
    # Backpressure – שרשרת הסיבות, וההגנות
    # -------------------------------------------------------------------------
    slide = add_slide("כשהמנוי איטי: Backpressure")

    tf = slide.shapes.add_textbox(Inches(0.7), Inches(1.34), Inches(11.933), Inches(1.0)).text_frame
    tf.word_wrap = True
    p = tf.paragraphs[0]
    r = p.add_run()
    r.text = "Backpressure = "
    r.font.size = Pt(16); r.font.bold = True; r.font.color.rgb = COLOR_BLUE
    r = p.add_run()
    r.text = ("היצרן מייצר מהר יותר ממה שהצרכן מספיק לעכל. ההודעות לא נעלמות – הן נערמות.")
    r.font.size = Pt(16); r.font.color.rgb = COLOR_DARK
    p.alignment = PP_ALIGN.RIGHT

    p2 = tf.add_paragraph()
    r = p2.add_run()
    r.text = "והן נערמות אצל הברוקר, לא אצל הצרכן: "
    r.font.size = Pt(14); r.font.bold = True; r.font.color.rgb = COLOR_ALERT
    r = p2.add_run()
    r.text = ("הצרכן הוא שירות נפרד שרץ אצלכם ורק מושך; התור עצמו הוא מבנה נתונים בתוך "
              "הברוקר. ההודעה תופסת את זיכרונו עד שהצרכן מסיים ומאשר אותה (ack).")
    r.font.size = Pt(14); r.font.color.rgb = COLOR_DARK
    p2.alignment = PP_ALIGN.RIGHT

    CHAIN = [
        (10.4, "1. הצרכן איטי", "מייצרים 1,000 בשנייה, מעכלים 800"),
        (7.4, "2. התור תופח", "והוא יושב בזיכרון של הברוקר"),
        (4.4, "3. זיכרון הברוקר נגמר", "הוא מחזיק את הערימה, לא הצרכן"),
        (1.4, "4. חסימת יצרנים", "בשרת הברוקר – כל מי שמפרסם אליו נעצר"),
    ]
    for i, (x, title, sub) in enumerate(CHAIN):
        last = i == len(CHAIN) - 1
        box(slide, x, 2.45, 2.6, 0.95, title, sub, size=13,
            fill=RGBColor(253, 234, 234) if last else COLOR_GRAY,
            fg=COLOR_ALERT if last else COLOR_NAVY,
            line=COLOR_ALERT if last else None)
        if i < len(CHAIN) - 1:
            arrow(slide, x - 0.65, 2.82, w=0.60, h=0.24)

    tf = slide.shapes.add_textbox(Inches(0.7), Inches(3.52), Inches(11.933), Inches(0.5)).text_frame
    tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = ("הברוקר עוצר את כל מי שמפרסם אליו, ולא רק את התור שהתפוצץ. התוצאה: גם המחסן, "
              "שהצרכן שלו בריא לגמרי, מפסיק לקבל עבודה חדשה.")
    p.font.size = Pt(13); p.font.bold = True; p.font.color.rgb = COLOR_ALERT
    p.alignment = PP_ALIGN.RIGHT

    add_bullets(slide, [
        ("ההגנה הראשונה – QoS (prefetch):",
         "השם מטעה: לא איכות ולא עדיפות, אלא «מספר» – כמה הודעות הברוקר שולח לצרכן לפני "
         "שהוא עוצר וממתין לאישור (ack). ב-prefetch=1 הצרכן מקבל הודעה אחת בכל פעם. "
         "ערך נמוך משאיר את ההיערמות אצל הברוקר, «שיודע לנהל אותה», במקום להפיל את הצרכן."),
        ("ההגנה השנייה – DLQ:",
         "Dead Letter Queue – הודעה שנכשלה שוב ושוב עוברת אחרי כמה ניסיונות לתור צדדי, "
         "כדי שלא תחסום את מי שמאחוריה. מה שיושב שם הוא רשימת התקלות של המערכת, "
         "וצריך להתריע עליו."),
    ], size=16, top=4.15, height=2.5)

    notes(slide,
        "שאלה צפויה: 'למה לא להוסיף עוד ברוקרים או רפליקות?' – זו בעיית קצב ולא בעיית "
        "קיבולת: מייצרים 1,000 ומעכלים 800, ולכן נצברות 200 בשנייה ללא חסם. "
        "להפריד שלושה דברים שנשמעים דומה: רפליקה היא עותק של אותו תור לזמינות – "
        "ולא רק שאינה עוזרת, היא מחמירה, כי כל הודעה נכתבת לכל העותקים. "
        "אשכול מחלק תורים שונים בין nodes, אך אינו מחלק תור בודד. "
        "ורק פיצול לפי מפתח באמת מחלק את העומס – אותו מנגנון שראינו בשקף של Direct. "
        "הקריטריון: מה שמוסיף קצב עיכול פותר, מה שמוסיף מקום אחסון רק דוחה.\n\n"
        "אנלוגיה אם הבלבול חוזר: הברוקר הוא סניף הדואר, הצרכן הוא השליח שבא לאסוף. "
        "שליח איטי לא נחנק – החבילות נערמות במחסן של הסניף, ולסניף נגמרות המדפים."
    )

    # -------------------------------------------------------------------------
    # 7. Topic  (~1.5 דקות)
    # -------------------------------------------------------------------------
    slide = add_slide("3. Topic Routing – ניתוב לפי תבנית")
    add_bullets(slide, [
        ("מה זה 'תבנית'?",
         "המפתח בנוי כמו כתובת מדורגת – order.il.south: תחום, מדינה, אזור. תבנית היא אותה "
         "כתובת עם «'לא משנה לי'» באחד המקומות, כמו לומר לדוור 'כל מכתב מרחוב הרצל, "
         "לא חשוב איזה בית'."),
        ("שני סוגים של 'לא משנה לי':",
         "כוכבית * תופסת «מילה אחת בדיוק». סולמית # תופסת «את כל מה שנשאר» – כמה מילים "
         "שיהיו, וגם אפס."),
    ], size=18, top=1.45, height=1.9)

    match = add_table(slide,
        ["order.il.#", "order.il.*", "המפתח שמגיע"],
        [
            ["כן", "כן", "order.il.south"],
            ["כן", "לא", "order.il.south.express"],
            ["כן", "לא", "order.il"],
        ],
        top=3.45, height=1.9, size=16,
    )
    for r in range(1, 4):                       # צביעת כן/לא – ירוק מול אדום
        for c in (0, 1):
            cell = match.cell(r, c)
            for p in cell.text_frame.paragraphs:
                p.font.bold = True
                p.font.color.rgb = COLOR_OK if cell.text == "כן" else COLOR_ALERT
                p.alignment = PP_ALIGN.CENTER

    add_caption(slide,
        "השורה השלישית היא העיקר: # תופס גם אפס מילים. והסכנה השקטה – מוסכמת השמות הופכת "
        "לחוזה בין הצוותים, ושינוי סדר המילים שובר מנויים קיימים בלי שאף אחד יקבל שגיאה.",
        top=5.55,
    )
    notes(slide,
        "שאלה טובה לקהל לפני שחושפים את הטבלה: 'מה יתפוס order.*.south?' – "
        "התשובה מקבעת את ההבדל בין * ל-# בשנייה."
    )

    # -------------------------------------------------------------------------
    # 8. הסיפור – הזרימה התקינה  (~1.5 דקות)
    # -------------------------------------------------------------------------
    def flow_diagram(slide, broken=False, focus=None, only=None):
        """מציירת את הזרימה המלאה. broken=True מסמנת את שלוש נקודות הכשל.
        focus ב-'A'/'B'/'C' משאיר מסלול אחד בצבע מלא ומעמעם את השאר, ו-only ב-1/2/3
        עושה זאת לכשל בודד – כך נבנה אפקט ההדגשה ההדרגתי בלי אנימציות,
        שאינן שורדות המרה ל-Google Slides."""
        from pptx.enum.shapes import MSO_SHAPE as _SHAPE

        LANE_A, LANE_B, LANE_C = 2.275, 3.85, 5.425
        ROWS_B = [3.00, 3.60, 4.20]
        X_PROD, X_EX, X_QUEUE, X_CONS = 11.4, 8.5, 5.5, 1.6
        RED_FILL = RGBColor(253, 234, 234)
        DIM_FILL, DIM_FG, DIM_LINE = (RGBColor(247, 248, 249), RGBColor(188, 192, 198),
                                      RGBColor(216, 220, 224))

        def on(lane):
            return focus is None or focus == lane

        def tone(lane, fill=COLOR_GRAY, fg=COLOR_NAVY, line=None):
            """צבעי תיבה לפי המסלול – מלאים אם הוא במוקד, מעומעמים אחרת."""
            if on(lane):
                return dict(fill=fill, fg=fg, line=line)
            return dict(fill=DIM_FILL, fg=DIM_FG, line=DIM_LINE)

        DIMMED = dict(fill=DIM_FILL, fg=DIM_FG, line=DIM_LINE)

        def sty(lane, fail_no=None):
            """אדום לכשל שבמוקד, מעומעם לכל השאר בשקף בנייה, ורגיל אחרת."""
            if broken and fail_no is not None and (only is None or only == fail_no):
                return dict(fill=RED_FILL, fg=COLOR_ALERT, line=COLOR_ALERT)
            if only is not None:
                return DIMMED
            return tone(lane)

        def sty_ex(lane):
            return DIMMED if only is not None else tone(lane, fill=COLOR_NAVY, fg=COLOR_WHITE)

        def arrow_color(lane):
            return DIM_LINE if (only is not None or not on(lane)) else None

        def col_header(x, w, text):
            tf = slide.shapes.add_textbox(Inches(x), Inches(1.32), Inches(w), Inches(0.3)).text_frame
            p = tf.paragraphs[0]
            p.text = text
            p.font.size = Pt(12)
            p.font.bold = True
            p.font.color.rgb = COLOR_BLUE
            p.alignment = PP_ALIGN.CENTER

        def key_label(y, text, active=True):
            """מפתח ה-Binding – יושב על החץ שבין ה-Exchange לתור."""
            tf = slide.shapes.add_textbox(Inches(7.52), Inches(y), Inches(0.96), Inches(0.28)).text_frame
            p = tf.paragraphs[0]
            p.text = text
            p.font.size = Pt(9)
            p.font.color.rgb = COLOR_DARK if active else DIM_FG
            p.alignment = PP_ALIGN.CENTER

        # מסגרת הברוקר נוספת ראשונה כדי שתישאר מאחורי התיבות (z-order = סדר ההוספה)
        frame = slide.shapes.add_shape(_SHAPE.ROUNDED_RECTANGLE,
                                       Inches(5.3), Inches(1.75), Inches(5.4), Inches(4.55))
        frame.fill.background()
        frame.line.color.rgb = COLOR_BLUE
        frame.line.width = Pt(1.25)
        frame.line.dash_style = pptx.enum.dml.MSO_LINE_DASH_STYLE.DASH

        tf = slide.shapes.add_textbox(Inches(5.3), Inches(5.93), Inches(5.4), Inches(0.3)).text_frame
        p = tf.paragraphs[0]
        p.text = "Message Broker – שרת אחד שמכיל את שניהם"
        p.font.size = Pt(11)
        p.font.bold = True
        p.font.color.rgb = COLOR_BLUE
        p.alignment = PP_ALIGN.CENTER

        for x, w, t in [(X_PROD, 1.7, "Producer"), (X_EX, 2.0, "Exchange"),
                        (X_QUEUE, 2.0, "Queue"), (X_CONS, 2.4, "Consumer")]:
            col_header(x, w, t)

        box(slide, X_PROD, 3.35, 1.7, 1.0, "שירות ההזמנות", "מפרסם OrderPlaced",
            fill=COLOR_BLUE, fg=COLOR_WHITE, size=12)
        rule(slide, 10.95, LANE_A, 0.05, LANE_C - LANE_A)
        rule(slide, 10.95, 3.82, 0.45, 0.06)

        for y, lane in ((LANE_A, "A"), (LANE_B, "B"), (LANE_C, "C")):
            c = arrow_color(lane)
            arrow(slide, 10.50, y - 0.14, w=0.45, color=c)
            arrow(slide, 7.60, y - 0.14, w=0.90, color=c)

        for y, lane, name in [(LANE_A, "A", "Direct"), (LANE_B, "B", "Fanout"),
                              (LANE_C, "C", "Topic")]:
            box(slide, X_EX, y - 0.375, 2.0, 0.75, name, **sty_ex(lane))
        key_label(LANE_A - 0.52, "orders.charge", on("A") and only is None)
        key_label(LANE_B - 0.52, "ללא מפתח", on("B") and only is None)
        key_label(LANE_C - 0.52, "order.il.south.#", on("C") and only is None)

        box(slide, X_QUEUE, LANE_A - 0.375, 2.0, 0.75, "charge-queue", **sty("A"))
        box(slide, X_CONS, LANE_A - 0.375, 2.4, 0.75,
            "עשרה Workers  (3)" if broken else "עשרה Workers", "חיוב הכרטיס",
            **sty("A", 3))
        arrow(slide, 4.30, LANE_A - 0.11, w=1.0, h=0.22, color=arrow_color("A"))

        for i, (y, q, c) in enumerate(zip(ROWS_B, ["warehouse", "analytics", "sms"],
                                          ["מחסן", "אנליטיקה", "SMS ללקוח"])):
            fail_no = {1: 1, 2: 2}.get(i)
            tag = f"  ({fail_no})" if (broken and fail_no) else ""
            box(slide, X_QUEUE, y, 2.0, 0.5, q + tag, size=11, **sty("B", fail_no))
            box(slide, X_CONS, y, 2.4, 0.5, c, size=11, **sty("B", fail_no))
            arrow(slide, 4.30, y + 0.14, w=1.0, h=0.22, color=arrow_color("B"))

        box(slide, X_QUEUE, LANE_C - 0.375, 2.0, 0.75, "south-queue", **sty("C"))
        box(slide, X_CONS, LANE_C - 0.375, 2.4, 0.75, "מחסן באר שבע", "רק הזמנות הדרום",
            **sty("C"))
        arrow(slide, 4.30, LANE_C - 0.11, w=1.0, h=0.22, color=arrow_color("C"))

        legend = [
            ("Exchange – שולחן המיון בתוך הברוקר: היצרן שולח אליו ולא לתור, והוא מחליט מי מקבל "
             "עותק.   |   Queue – התור שבו ההודעה ממתינה עד שהצרכן פנוי.", COLOR_BLUE),
            ("Binding – החוק שמחבר Exchange לתור, והמחרוזת שעל החץ היא הקריטריון שלו. לדוגמה: "
             "'כל הודעה שמפתחה orders.charge ← עותק ל-charge-queue'. את החוק רושם השירות "
             "שקורא מהתור – המחסן מצהיר על warehouse ורושם את החוק שלו – ולא השולח. "
             "לכן מנוי חדש אינו נוגע בקוד של היצרן.", COLOR_BLUE),
            ("Consumer – מי שמעבד בפועל, ורץ מחוץ לברוקר. היצרן לא מכיר אף אחד מהם, "
             "וזו כל הנקודה.", COLOR_BLUE),
        ] if not broken else [
            ("(1) האנליטיקה איטית: התור תופח, הברוקר מגיע לסף הזיכרון וחוסם את היצרנים – "
             "וגם המחסן, שבריא לגמרי, מורעב. הפתרון: prefetch נמוך.", COLOR_ALERT),
            ("(2) ה-SMS נכשל שוב ושוב: ההודעה חוזרת לתור וחוסמת את מה שמאחוריה. "
             "הפתרון: DLQ – תור צדדי, והתראה.", COLOR_ALERT),
            ("(3) ה-Worker חייב את הכרטיס ומת לפני ה-ack: הברוקר ישלח שוב, והלקוח יחויב פעמיים. "
             "זו ברירת המחדל at-least-once – ולכן כל צרכן חייב להיות אידמפוטנטי.", COLOR_ALERT),
        ]
        if only is not None:
            legend = [legend[only - 1]]
        tf = slide.shapes.add_textbox(Inches(0.7), Inches(6.36), Inches(11.933), Inches(1.0)).text_frame
        tf.word_wrap = True
        for idx, (text, color) in enumerate(legend):
            p = tf.paragraphs[0] if idx == 0 else tf.add_paragraph()
            p.text = text
            p.font.size = Pt(11)
            p.font.italic = not broken
            p.font.bold = broken
            p.font.color.rgb = color
            p.alignment = PP_ALIGN.RIGHT

    # -------------------------------------------------------------------------
    # הזרימה – שלושה שקפי בנייה, מסלול אחד מודגש בכל אחד
    # -------------------------------------------------------------------------
    for lane, title, note in [
        ("A", "הזרימה, שלב 1: פקודה ← Direct",
         "הלקוח לוחץ 'אישור הזמנה'. שירות ההזמנות מפרסם ל-Exchange ולא מכיר אף תור. "
         "ה-Binding הוא המחרוזת orders.charge; עשרה Workers מושכים מהתור, "
         "וכל הודעה נמסרת לאחד בלבד. זו פקודה – יש לה בעל אחד."),
        ("B", "הזרימה, שלב 2: אירוע ← Fanout",
         "אותה הודעה, מסלול אחר: אין מפתח כלל, ועותק נכנס לכל תור מחובר. "
         "מנוי רביעי הוא Binding נוסף – בלי לגעת בשולח. זה אירוע, לא פקודה."),
        ("C", "הזרימה, שלב 3: תבנית ← Topic",
         "ה-Binding כאן הוא תבנית: order.il.south.# תופס רק את הזמנות הדרום. "
         "המחסן בבאר שבע לא רואה הזמנות של אזורים אחרים."),
    ]:
        slide = add_slide(title)
        flow_diagram(slide, focus=lane)
        notes(slide, note)

    # -------------------------------------------------------------------------
    # הזרימה המלאה – הכול יחד
    # -------------------------------------------------------------------------
    slide = add_slide("הזרימה המלאה: ההזמנה של 14:03, מהיצרן ועד הצרכן")
    flow_diagram(slide)
    notes(slide,
        "התמונה המלאה, אחרי שלושת שקפי הבנייה. המשפט לומר כאן: הזמנה אחת שנכנסה ב-14:03 "
        "הפעילה את שלוש האסטרטגיות ברצף – הן לא חלופות מתחרות. השולח כתב שורת פרסום אחת בלבד. "
        "השעה חשובה כי בשקף הבא חוזרים לאותה הזמנה שתי דקות מאוחר יותר."
    )

    # -------------------------------------------------------------------------
    # אותה זרימה – שלוש נקודות כשל
    # -------------------------------------------------------------------------
    for n, title, note in [
        (1, "כשל 1: המנוי האיטי חוסם את כולם",
         "האנליטיקה מעכלת לאט. התור שלה תופח בזיכרון הברוקר, הברוקר מגיע לסף שלו וחוסם את "
         "היצרנים – וגם המחסן, שבריא לגמרי, מפסיק לקבל עבודה. ההגנה: prefetch נמוך."),
        (2, "כשל 2: ההודעה שנכשלת שוב ושוב",
         "שירות ה-SMS נכשל, ההודעה חוזרת לתור ונכשלת שוב – וחוסמת את מה שמאחוריה. "
         "ההגנה: DLQ, ואחריו התראה. מה שיושב שם הוא רשימת התקלות של המערכת."),
        (3, "כשל 3: חיוב שקורה פעמיים",
         "ה-Worker חייב את הכרטיס ומת לפני ה-ack. הברוקר לא יודע שהעבודה בוצעה, ולכן ישלח "
         "את ההודעה שוב. זו ברירת המחדל at-least-once, ולא תקלה נדירה – "
         "ולכן כל צרכן חייב להיות אידמפוטנטי."),
    ]:
        slide = add_slide(title)
        flow_diagram(slide, broken=True, only=n)
        notes(slide, note)

    slide = add_slide("אותה הזמנה, 14:05: שלוש נקודות כשל")
    flow_diagram(slide, broken=True)
    notes(slide,
        "אותה תמונה בדיוק – ולכן אפשר לדבר במקום להסביר מחדש. המסר: כל תקלה כאן היא תוצאה "
        "ישירה של אסטרטגיית הניתוב שנבחרה. אלה לא מקרי קצה, אלה השבועיים הראשונים בפרודקשן."
    )

    # -------------------------------------------------------------------------
    # 10. השוואה ובחירה  (~1 דקה)
    # -------------------------------------------------------------------------
    slide = add_slide("השוואה: מתי בוחרים במה")
    add_table(slide,
        ["ה-Trade-off", "בוחרים בה כאשר...", "אסטרטגיה"],
        [
            ["צימוד לשם התור; הסדר נשבר בהרחבה", "יש נמען אחד ברור לעבודה", "Direct"],
            ["כל מנוי נוסף מכפיל את עלות הכתיבה", "כולם צריכים לדעת, ואינכם יודעים מי 'כולם'", "Fanout"],
            ["מוסכמת השמות הופכת לחוזה נוקשה", "לאירועים יש היררכיה טבעית (אזור, חומרה, סוג)", "Topic"],
        ],
        height=3.6,
        size=17,
    )
    add_caption(slide,
        "כלל האצבע: בחרו את הפשוטה ביותר שעונה על הדרישה. לעלות ברמת התחכום תמיד אפשר – "
        "לרדת ממנה, אחרי שכבר יש מנויים בפרודקשן, זה כבר פרויקט.",
        top=5.4,
    )
    notes(slide, "אם נגמר הזמן – זה השקף שאפשר לקצר לשתי שניות ולעבור לסיכום.")

    # -------------------------------------------------------------------------
    # 11. סיכום  (~30 שניות)
    # -------------------------------------------------------------------------
    slide = add_slide("סיכום")
    tf = slide.shapes.add_textbox(Inches(0.7), Inches(1.9), Inches(11.933), Inches(4.2)).text_frame
    tf.word_wrap = True
    for idx, text in enumerate([
        "1. ככל שהברוקר מסתכל עמוק יותר במעטפה – הניתוב חכם יותר, ויקר יותר.",
        "2. אלו לא חלופות מתחרות: הזמנה אחת מפעילה את שלושתן ברצף.",
        "3. אסטרטגיית הניתוב קובעת גם סדר וגם כפילויות – ולכן כל צרכן חייב להיות אידמפוטנטי.",
    ]):
        p = tf.paragraphs[0] if idx == 0 else tf.add_paragraph()
        p.text = text
        p.font.size = Pt(21)
        p.font.color.rgb = COLOR_DARK
        p.alignment = PP_ALIGN.RIGHT
        p.space_after = Pt(18)

    p = tf.add_paragraph()
    p.text = "\n\"ארכיטקטורת תוכנה טובה היא לא איך לחבר מערכות – אלא איך לנתק ביניהן בצורה חכמה.\""
    p.font.size = Pt(22)
    p.font.bold = True
    p.font.color.rgb = COLOR_BLUE
    p.alignment = PP_ALIGN.CENTER

    apply_rtl(prs)

    file_name = "Message_Routing_Strategies.pptx"
    prs.save(file_name)
    print(f"הקובץ נוצר בהצלחה: {file_name} ({len(prs.slides)} שקפים)")


if __name__ == "__main__":
    create_routing_strategies_presentation()
