delete from public.practitioner_offers where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol');
delete from public.practitioner_sections where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol');
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'de', 10, 'Mentoring', 'CHF 1''275', '3 Monate · online oder Praxis Schüpfheim', array['Aktionspreis bis 31.08.2026: CHF 1''199'], 'service', 'https://migraineme.app/img/practitioners/steph-mentoring.jpg');
update public.practitioner_offers set bullets = array_prepend('Der zentrale Weg zu stabiler Ruhe im Alltag. Online und auf Wunsch vor Ort.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='de' and sort=10;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'de', 20, 'Einzelsession', null, 'Online oder vor Ort', array[]::text[], 'service', 'https://migraineme.app/img/practitioners/steph-einzelsession.jpg');
update public.practitioner_offers set bullets = array_prepend('Punktuelle Entlastung & erstes Eintauchen. Online und auf Wunsch vor Ort.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='de' and sort=20;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'de', 30, 'Kids & Teens', null, 'Vor Ort · online ab ca. 12 Jahren', array[]::text[], 'service', 'https://migraineme.app/img/practitioners/steph-kids.jpg');
update public.practitioner_offers set bullets = array_prepend('Stark von innen. Vor Ort. Online möglich ab ca. 12 Jahren.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='de' and sort=30;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'de', 40, 'Exklusiv', null, 'Exklusivcoaching in den Bergen', array[]::text[], 'service', null);
update public.practitioner_offers set bullets = array_prepend('Intensive Auszeit mit Begleitung. Vor Ort in der Schweizer Bergwelt.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='de' and sort=40;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'de', 50, 'Unternehmen', null, 'Vor Ort im Unternehmen oder in der Seminarlocation', array[]::text[], 'service', null);
update public.practitioner_offers set bullets = array_prepend('Stabile Mitarbeitende. Klare Führung. Gesunde Leistung.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='de' and sort=50;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'de', 60, 'Kennenlerngespräch', 'kostenlos', 'ca. 20 Minuten · online', array[]::text[], 'intro', null);
update public.practitioner_offers set bullets = array_prepend('Schauen, ob es passt, bevor irgendetwas beginnt.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='de' and sort=60;
insert into public.practitioner_sections (practitioner_id, lang, sort, title, body, items)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'de', 10, 'Wie es verläuft', 'Klient:innen berichten diese Phasen im Verlauf des Mentorings.', array['Phase 1 · Spürbare Entlastung — Körperliche und mentale Anspannung lässt nach, Schmerz- und Stresssymptome gehen zurück, die Gedanken werden ruhiger.','Phase 2 · Mehr Selbstkontakt im Alltag — Der Kontakt zu sich selbst wird wieder zugänglich, Situationen werden mit mehr Ruhe, Leichtigkeit und Vertrauen erlebt.','Phase 3 · Tragende Stabilität und Lebensfreude — Herausforderungen bleiben bewältigbar, innere Stabilität und Lebensfreude kehren zurück.']);
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'en', 10, 'Mentoring', 'CHF 1''275', '3 months · online or Schüpfheim practice', array['Promotional rate to 31 Aug 2026: CHF 1''199'], 'service', 'https://migraineme.app/img/practitioners/steph-mentoring.jpg');
update public.practitioner_offers set bullets = array_prepend('The main way back to steady calm in daily life. Online, and in person on request.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='en' and sort=10;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'en', 20, 'Single session', null, 'Online or in person', array[]::text[], 'service', 'https://migraineme.app/img/practitioners/steph-einzelsession.jpg');
update public.practitioner_offers set bullets = array_prepend('Relief where it is needed, and a first taste. Online, and in person on request.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='en' and sort=20;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'en', 30, 'Kids & teens', null, 'In person · online from about age 12', array[]::text[], 'service', 'https://migraineme.app/img/practitioners/steph-kids.jpg');
update public.practitioner_offers set bullets = array_prepend('Strong from the inside. In person. Online from about age 12.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='en' and sort=30;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'en', 40, 'Exclusive', null, 'Exclusive coaching in the mountains', array[]::text[], 'service', null);
update public.practitioner_offers set bullets = array_prepend('An intensive retreat with her alongside you. In the Swiss mountains.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='en' and sort=40;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'en', 50, 'Organisations', null, 'On site at the company or a seminar venue', array[]::text[], 'service', null);
update public.practitioner_offers set bullets = array_prepend('Steady staff. Clear leadership. Healthy performance.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='en' and sort=50;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'en', 60, 'Intro call', 'free', 'About 20 minutes · online', array[]::text[], 'intro', null);
update public.practitioner_offers set bullets = array_prepend('See whether it fits, before anything begins.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='en' and sort=60;
insert into public.practitioner_sections (practitioner_id, lang, sort, title, body, items)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'en', 10, 'How it unfolds', 'Clients describe these phases over the course of the mentoring.', array['Phase 1 · Noticeable relief — Physical and mental tension eases, pain and stress symptoms recede, thoughts grow quieter.','Phase 2 · More contact with yourself, day to day — Self-contact becomes reachable again, and situations are met with more calm, ease and trust.','Phase 3 · Stability that carries, and appetite for life — Challenges stay manageable, inner stability and the capacity for joy come back.']);
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'nl', 10, 'Mentoring', 'CHF 1''275', '3 maanden · online of praktijk Schüpfheim', array['Actieprijs t/m 31-08-2026: CHF 1''199'], 'service', 'https://migraineme.app/img/practitioners/steph-mentoring.jpg');
update public.practitioner_offers set bullets = array_prepend('De centrale weg naar stabiele rust in het dagelijks leven. Online en op verzoek op locatie.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='nl' and sort=10;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'nl', 20, 'Losse sessie', null, 'Online of op locatie', array[]::text[], 'service', 'https://migraineme.app/img/practitioners/steph-einzelsession.jpg');
update public.practitioner_offers set bullets = array_prepend('Verlichting waar het nodig is, en een eerste kennismaking. Online en op verzoek op locatie.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='nl' and sort=20;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'nl', 30, 'Kids & teens', null, 'Op locatie · online vanaf ongeveer 12 jaar', array[]::text[], 'service', 'https://migraineme.app/img/practitioners/steph-kids.jpg');
update public.practitioner_offers set bullets = array_prepend('Sterk van binnen. Op locatie. Online vanaf ongeveer 12 jaar.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='nl' and sort=30;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'nl', 40, 'Exclusief', null, 'Exclusieve coaching in de bergen', array[]::text[], 'service', null);
update public.practitioner_offers set bullets = array_prepend('Een intensieve time-out met begeleiding. In de Zwitserse bergen.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='nl' and sort=40;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'nl', 50, 'Organisaties', null, 'Op locatie bij het bedrijf of in een seminarlocatie', array[]::text[], 'service', null);
update public.practitioner_offers set bullets = array_prepend('Stabiele medewerkers. Heldere leiding. Gezonde prestaties.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='nl' and sort=50;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'nl', 60, 'Kennismakingsgesprek', 'gratis', 'Ongeveer 20 minuten · online', array[]::text[], 'intro', null);
update public.practitioner_offers set bullets = array_prepend('Kijken of het past, voordat er iets begint.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='nl' and sort=60;
insert into public.practitioner_sections (practitioner_id, lang, sort, title, body, items)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'nl', 10, 'Hoe het verloopt', 'Cliënten beschrijven deze fasen in de loop van de begeleiding.', array['Fase 1 · Voelbare verlichting — Lichamelijke en mentale spanning neemt af, pijn- en stressklachten verminderen, gedachten worden rustiger.','Fase 2 · Meer contact met jezelf in het dagelijks leven — Het contact met jezelf wordt weer bereikbaar en situaties voelen rustiger en lichter.','Fase 3 · Dragende stabiliteit en levensvreugde — Uitdagingen blijven hanteerbaar, innerlijke stabiliteit en levensvreugde keren terug.']);
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'fr', 10, 'Mentorat', 'CHF 1''275', '3 mois · en ligne ou cabinet de Schüpfheim', array['Tarif promotionnel jusqu''au 31.08.2026 : CHF 1''199'], 'service', 'https://migraineme.app/img/practitioners/steph-mentoring.jpg');
update public.practitioner_offers set bullets = array_prepend('Le chemin principal vers un calme stable au quotidien. En ligne, et au cabinet sur demande.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='fr' and sort=10;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'fr', 20, 'Séance unique', null, 'En ligne ou sur place', array[]::text[], 'service', 'https://migraineme.app/img/practitioners/steph-einzelsession.jpg');
update public.practitioner_offers set bullets = array_prepend('Un soulagement ponctuel et une première immersion. En ligne, et au cabinet sur demande.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='fr' and sort=20;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'fr', 30, 'Enfants & ados', null, 'Sur place · en ligne à partir d''environ 12 ans', array[]::text[], 'service', 'https://migraineme.app/img/practitioners/steph-kids.jpg');
update public.practitioner_offers set bullets = array_prepend('Fort de l''intérieur. Sur place. En ligne à partir d''environ 12 ans.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='fr' and sort=30;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'fr', 40, 'Exclusif', null, 'Coaching exclusif en montagne', array[]::text[], 'service', null);
update public.practitioner_offers set bullets = array_prepend('Une parenthèse intensive accompagnée. Dans les montagnes suisses.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='fr' and sort=40;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'fr', 50, 'Entreprises', null, 'Sur place en entreprise ou en lieu de séminaire', array[]::text[], 'service', null);
update public.practitioner_offers set bullets = array_prepend('Des équipes stables. Un encadrement clair. Une performance saine.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='fr' and sort=50;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'fr', 60, 'Premier échange', 'gratuit', 'Environ 20 minutes · en ligne', array[]::text[], 'intro', null);
update public.practitioner_offers set bullets = array_prepend('Voir si cela convient, avant que quoi que ce soit ne commence.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='fr' and sort=60;
insert into public.practitioner_sections (practitioner_id, lang, sort, title, body, items)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'fr', 10, 'Comment cela se déroule', 'Les client·es décrivent ces phases au fil de l''accompagnement.', array['Phase 1 · Un soulagement perceptible — La tension physique et mentale diminue, les douleurs et le stress reculent, les pensées s''apaisent.','Phase 2 · Plus de contact avec soi au quotidien — Le contact avec soi redevient accessible, les situations se vivent avec plus de calme et de confiance.','Phase 3 · Une stabilité qui porte, et le goût de vivre — Les défis restent gérables, la stabilité intérieure et la joie reviennent.']);
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'es', 10, 'Mentoría', 'CHF 1''275', '3 meses · en línea o consulta de Schüpfheim', array['Precio promocional hasta el 31.08.2026: CHF 1''199'], 'service', 'https://migraineme.app/img/practitioners/steph-mentoring.jpg');
update public.practitioner_offers set bullets = array_prepend('El camino central hacia una calma estable en el día a día. En línea y presencial si lo pides.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='es' and sort=10;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'es', 20, 'Sesión suelta', null, 'En línea o presencial', array[]::text[], 'service', 'https://migraineme.app/img/practitioners/steph-einzelsession.jpg');
update public.practitioner_offers set bullets = array_prepend('Alivio puntual y una primera inmersión. En línea y presencial si lo pides.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='es' and sort=20;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'es', 30, 'Niños y adolescentes', null, 'Presencial · en línea a partir de unos 12 años', array[]::text[], 'service', 'https://migraineme.app/img/practitioners/steph-kids.jpg');
update public.practitioner_offers set bullets = array_prepend('Fuerte por dentro. Presencial. En línea a partir de unos 12 años.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='es' and sort=30;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'es', 40, 'Exclusivo', null, 'Coaching exclusivo en la montaña', array[]::text[], 'service', null);
update public.practitioner_offers set bullets = array_prepend('Un retiro intensivo acompañada. En las montañas suizas.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='es' and sort=40;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'es', 50, 'Empresas', null, 'En la empresa o en una sala de seminarios', array[]::text[], 'service', null);
update public.practitioner_offers set bullets = array_prepend('Personas estables. Liderazgo claro. Rendimiento sano.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='es' and sort=50;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'es', 60, 'Primera charla', 'gratis', 'Unos 20 minutos · en línea', array[]::text[], 'intro', null);
update public.practitioner_offers set bullets = array_prepend('Ver si encaja, antes de empezar nada.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='es' and sort=60;
insert into public.practitioner_sections (practitioner_id, lang, sort, title, body, items)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'es', 10, 'Cómo transcurre', 'Las clientas describen estas fases a lo largo del acompañamiento.', array['Fase 1 · Alivio perceptible — La tensión física y mental cede, el dolor y el estrés remiten, los pensamientos se calman.','Fase 2 · Más contacto contigo en el día a día — El contacto contigo vuelve a estar al alcance y las situaciones se viven con más calma y confianza.','Fase 3 · Una estabilidad que sostiene, y ganas de vivir — Los retos siguen siendo manejables, vuelven la estabilidad interior y la alegría.']);
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'it', 10, 'Mentoring', 'CHF 1''275', '3 mesi · online o studio di Schüpfheim', array['Prezzo promozionale fino al 31.08.2026: CHF 1''199'], 'service', 'https://migraineme.app/img/practitioners/steph-mentoring.jpg');
update public.practitioner_offers set bullets = array_prepend('La via centrale verso una calma stabile nella vita di tutti i giorni. Online e in studio su richiesta.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='it' and sort=10;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'it', 20, 'Seduta singola', null, 'Online o in presenza', array[]::text[], 'service', 'https://migraineme.app/img/practitioners/steph-einzelsession.jpg');
update public.practitioner_offers set bullets = array_prepend('Sollievo mirato e un primo assaggio. Online e in studio su richiesta.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='it' and sort=20;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'it', 30, 'Bambini e ragazzi', null, 'In presenza · online da circa 12 anni', array[]::text[], 'service', 'https://migraineme.app/img/practitioners/steph-kids.jpg');
update public.practitioner_offers set bullets = array_prepend('Forte da dentro. In presenza. Online da circa 12 anni.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='it' and sort=30;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'it', 40, 'Esclusivo', null, 'Coaching esclusivo in montagna', array[]::text[], 'service', null);
update public.practitioner_offers set bullets = array_prepend('Una pausa intensiva accompagnata. Nelle montagne svizzere.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='it' and sort=40;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'it', 50, 'Aziende', null, 'In azienda o in una sala seminari', array[]::text[], 'service', null);
update public.practitioner_offers set bullets = array_prepend('Persone stabili. Guida chiara. Prestazioni sane.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='it' and sort=50;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'it', 60, 'Primo colloquio', 'gratuito', 'Circa 20 minuti · online', array[]::text[], 'intro', null);
update public.practitioner_offers set bullets = array_prepend('Capire se va bene, prima che cominci qualsiasi cosa.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='it' and sort=60;
insert into public.practitioner_sections (practitioner_id, lang, sort, title, body, items)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'it', 10, 'Come si svolge', 'Le persone descrivono queste fasi nel corso del percorso.', array['Fase 1 · Un sollievo percepibile — La tensione fisica e mentale si allenta, dolore e stress arretrano, i pensieri si calmano.','Fase 2 · Più contatto con sé nella vita di tutti i giorni — Il contatto con sé torna raggiungibile e le situazioni si vivono con più calma e fiducia.','Fase 3 · Una stabilità che regge, e voglia di vivere — Le sfide restano gestibili, tornano la stabilità interiore e la gioia.']);
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'pt', 10, 'Mentoria', 'CHF 1''275', '3 meses · online ou consultório de Schüpfheim', array['Preço promocional até 31.08.2026: CHF 1''199'], 'service', 'https://migraineme.app/img/practitioners/steph-mentoring.jpg');
update public.practitioner_offers set bullets = array_prepend('O caminho central para uma calma estável no dia a dia. Online e presencial mediante pedido.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='pt' and sort=10;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'pt', 20, 'Sessão avulsa', null, 'Online ou presencial', array[]::text[], 'service', 'https://migraineme.app/img/practitioners/steph-einzelsession.jpg');
update public.practitioner_offers set bullets = array_prepend('Alívio pontual e um primeiro mergulho. Online e presencial mediante pedido.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='pt' and sort=20;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'pt', 30, 'Crianças e adolescentes', null, 'Presencial · online a partir dos 12 anos', array[]::text[], 'service', 'https://migraineme.app/img/practitioners/steph-kids.jpg');
update public.practitioner_offers set bullets = array_prepend('Forte por dentro. Presencial. Online a partir dos 12 anos.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='pt' and sort=30;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'pt', 40, 'Exclusivo', null, 'Coaching exclusivo na montanha', array[]::text[], 'service', null);
update public.practitioner_offers set bullets = array_prepend('Uma pausa intensiva acompanhada. Nas montanhas suíças.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='pt' and sort=40;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'pt', 50, 'Empresas', null, 'Na empresa ou numa sala de seminários', array[]::text[], 'service', null);
update public.practitioner_offers set bullets = array_prepend('Pessoas estáveis. Liderança clara. Desempenho saudável.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='pt' and sort=50;
insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'pt', 60, 'Conversa inicial', 'gratuita', 'Cerca de 20 minutos · online', array[]::text[], 'intro', null);
update public.practitioner_offers set bullets = array_prepend('Ver se encaixa, antes de começar seja o que for.', bullets)
 where practitioner_id=(select id from public.practitioners where slug='stephanie-konkol') and lang='pt' and sort=60;
insert into public.practitioner_sections (practitioner_id, lang, sort, title, body, items)
values ((select id from public.practitioners where slug='stephanie-konkol'), 'pt', 10, 'Como decorre', 'As pessoas descrevem estas fases ao longo do acompanhamento.', array['Fase 1 · Alívio percetível — A tensão física e mental cede, a dor e o stress recuam, os pensamentos acalmam.','Fase 2 · Mais contacto contigo no dia a dia — O contacto contigo volta a estar ao alcance e as situações vivem-se com mais calma e confiança.','Fase 3 · Uma estabilidade que sustenta, e vontade de viver — Os desafios continuam geríveis, voltam a estabilidade interior e a alegria.']);