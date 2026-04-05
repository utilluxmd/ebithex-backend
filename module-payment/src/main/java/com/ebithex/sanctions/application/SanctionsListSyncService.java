package com.ebithex.sanctions.application;

import com.ebithex.sanctions.domain.SanctionsEntry;
import com.ebithex.sanctions.domain.SanctionsSyncLog;
import com.ebithex.sanctions.infrastructure.SanctionsRepository;
import com.ebithex.sanctions.infrastructure.SanctionsSyncLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synchronise les listes de sanctions réglementaires depuis leurs sources officielles.
 *
 * <p>Trois listes sont gérées automatiquement :
 * <ul>
 *   <li><b>OFAC_SDN</b> — Office of Foreign Assets Control (U.S. Treasury)</li>
 *   <li><b>UN_CONSOLIDATED</b> — Liste consolidée des Nations Unies (Conseil de Sécurité)</li>
 *   <li><b>EU_CONSOLIDATED</b> — Liste consolidée de l'Union Européenne (FSF)</li>
 * </ul>
 *
 * <p>Les listes <b>ECOWAS_LOCAL</b> et <b>CUSTOM</b> sont importées manuellement
 * via {@link #importCsv(String, String)}.
 *
 * <p>Chaque opération est journalisée dans {@code sanctions_sync_log}.
 *
 * <p>Protection XXE activée sur tous les parseurs XML
 * (cf. OWASP XML External Entity Prevention Cheat Sheet).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SanctionsListSyncService {

    private final SanctionsRepository        sanctionsRepository;
    private final SanctionsSyncLogRepository syncLogRepository;

    @Value("${ebithex.sanctions.sync.ofac-url:https://www.treasury.gov/ofac/downloads/sdn.xml}")
    private String ofacUrl;

    @Value("${ebithex.sanctions.sync.un-url:https://scsanctions.un.org/resources/xml/en/consolidated.xml}")
    private String unUrl;

    @Value("${ebithex.sanctions.sync.eu-url:https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content}")
    private String euUrl;

    @Value("${ebithex.sanctions.sync.timeout-seconds:60}")
    private int timeoutSeconds;

    @Value("${ebithex.sanctions.sync.batch-size:500}")
    private int batchSize;

    /** Noms de toutes les listes gérées. */
    private static final List<String> ALL_AUTO_LISTS = List.of(
        "OFAC_SDN", "UN_CONSOLIDATED", "EU_CONSOLIDATED"
    );

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Synchronise les trois listes automatiques (OFAC, ONU, UE) en séquence.
     * Retourne un log par liste.
     */
    public List<SanctionsSyncLog> syncAll() {
        List<SanctionsSyncLog> results = new ArrayList<>();
        for (String listName : ALL_AUTO_LISTS) {
            results.add(syncList(listName));
        }
        return results;
    }

    /**
     * Synchronise une liste spécifique depuis son URL source officielle.
     * Uniquement pour OFAC_SDN, UN_CONSOLIDATED, EU_CONSOLIDATED.
     */
    public SanctionsSyncLog syncList(String listName) {
        long startMs = System.currentTimeMillis();
        log.info("Synchronisation liste de sanctions: {}", listName);
        try {
            String url     = resolveUrl(listName);
            byte[] content = downloadContent(url);
            List<SanctionsEntry> entries = parse(listName, new ByteArrayInputStream(content));
            return persistEntries(listName, entries, startMs);
        } catch (Exception e) {
            log.error("Échec synchronisation liste {}: {}", listName, e.getMessage(), e);
            return saveLog(listName, "FAILED", 0, e.getMessage(), System.currentTimeMillis() - startMs);
        }
    }

    /**
     * Importe des entrées depuis un contenu CSV manuel.
     *
     * <p>Format attendu (une entrée par ligne, sans entête) :
     * <pre>
     *   entityName,aliases,countryCode,entityType
     * </pre>
     * <ul>
     *   <li>{@code entityName} — obligatoire, peut être entre guillemets</li>
     *   <li>{@code aliases}    — optionnel, plusieurs valeurs séparées par {@code |}</li>
     *   <li>{@code countryCode} — optionnel, code ISO 3166-1 alpha-2</li>
     *   <li>{@code entityType} — optionnel, {@code INDIVIDUAL | ENTITY | VESSEL | AIRCRAFT}</li>
     * </ul>
     * Les lignes commençant par {@code #} sont ignorées.
     */
    @Transactional
    public SanctionsSyncLog importCsv(String listName, String csvContent) {
        long startMs = System.currentTimeMillis();
        log.info("Import CSV liste de sanctions: {}", listName);
        try {
            List<SanctionsEntry> entries = parseCsv(listName, csvContent);
            return persistEntries(listName, entries, startMs);
        } catch (Exception e) {
            log.error("Échec import CSV liste {}: {}", listName, e.getMessage(), e);
            return saveLog(listName, "FAILED", 0, e.getMessage(), System.currentTimeMillis() - startMs);
        }
    }

    /**
     * Retourne le dernier log de synchronisation par liste.
     * Les listes sans historique sont absentes de la map.
     */
    public Map<String, SanctionsSyncLog> getSyncStatus() {
        Map<String, SanctionsSyncLog> status = new LinkedHashMap<>();
        for (String list : List.of("OFAC_SDN", "UN_CONSOLIDATED", "EU_CONSOLIDATED", "ECOWAS_LOCAL", "CUSTOM")) {
            syncLogRepository.findTopByListNameOrderBySyncedAtDesc(list)
                .ifPresent(entry -> status.put(list, entry));
        }
        return status;
    }

    /** Retourne les N derniers logs toutes listes confondues. */
    public List<SanctionsSyncLog> getRecentLogs(int limit) {
        return syncLogRepository.findRecent(PageRequest.of(0, limit));
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Transactional
    protected SanctionsSyncLog persistEntries(String listName, List<SanctionsEntry> entries, long startMs) {
        if (entries.isEmpty()) {
            log.warn("Aucune entrée parsée pour la liste {}", listName);
            return saveLog(listName, "PARTIAL", 0, "Aucune entrée dans la source",
                System.currentTimeMillis() - startMs);
        }

        // Supprimer les anciennes entrées de cette liste (remplacées intégralement)
        sanctionsRepository.deleteByListName(listName);

        // Insertion en batches pour éviter les timeouts sur les grandes listes
        int imported = 0;
        for (int i = 0; i < entries.size(); i += batchSize) {
            List<SanctionsEntry> batch = entries.subList(i, Math.min(i + batchSize, entries.size()));
            sanctionsRepository.saveAll(batch);
            imported += batch.size();
        }

        long duration = System.currentTimeMillis() - startMs;
        log.info("Liste {} synchronisée : {} entrées en {}ms", listName, imported, duration);
        return saveLog(listName, "SUCCESS", imported, null, duration);
    }

    private SanctionsSyncLog saveLog(String listName, String status, int imported,
                                     String error, long durationMs) {
        return syncLogRepository.save(SanctionsSyncLog.builder()
            .listName(listName)
            .syncedAt(LocalDateTime.now())
            .status(status)
            .entriesImported(imported)
            .errorMessage(error)
            .durationMs(durationMs)
            .build());
    }

    // ── HTTP Download ─────────────────────────────────────────────────────────

    /**
     * Télécharge le contenu d'une URL.
     * Visibilité non-private pour permettre le stubbing dans les tests ({@code @SpyBean}).
     */
    public byte[] downloadContent(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("User-Agent", "Ebithex-SanctionsSync/1.0")
            .GET()
            .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " depuis " + url);
        }
        return response.body();
    }

    // ── Parseurs XML ──────────────────────────────────────────────────────────

    private List<SanctionsEntry> parse(String listName, InputStream content) throws Exception {
        return switch (listName) {
            case "OFAC_SDN"        -> parseOfac(content);
            case "UN_CONSOLIDATED" -> parseUn(content);
            case "EU_CONSOLIDATED" -> parseEu(content);
            default -> throw new IllegalArgumentException(
                "Pas de parseur XML pour la liste : " + listName + " — utilisez importCsv()");
        };
    }

    /**
     * Parse le fichier SDN de l'OFAC.
     * Source : {@code https://www.treasury.gov/ofac/downloads/sdn.xml}
     *
     * <p>Structure XML :
     * <pre>
     * &lt;sdnList&gt;
     *   &lt;sdnEntry&gt;
     *     &lt;lastName&gt;NOM&lt;/lastName&gt;
     *     &lt;firstName&gt;PRÉNOM&lt;/firstName&gt;
     *     &lt;sdnType&gt;Individual|Entity|Vessel|Aircraft&lt;/sdnType&gt;
     *     &lt;akaList&gt;&lt;aka&gt;&lt;lastName&gt;ALIAS&lt;/lastName&gt;&lt;/aka&gt;&lt;/akaList&gt;
     *     &lt;addressList&gt;&lt;address&gt;&lt;country&gt;PAYS&lt;/country&gt;&lt;/address&gt;&lt;/addressList&gt;
     *   &lt;/sdnEntry&gt;
     * &lt;/sdnList&gt;
     * </pre>
     */
    List<SanctionsEntry> parseOfac(InputStream content) throws Exception {
        Document doc = parseXml(content);
        NodeList sdnEntries = doc.getElementsByTagName("sdnEntry");
        List<SanctionsEntry> result = new ArrayList<>(sdnEntries.getLength());

        for (int i = 0; i < sdnEntries.getLength(); i++) {
            Element entry    = (Element) sdnEntries.item(i);
            String lastName  = textOf(entry, "lastName");
            String firstName = textOf(entry, "firstName");
            String sdnType   = textOf(entry, "sdnType");
            if (lastName == null || lastName.isBlank()) continue;

            String entityName = buildName(firstName, lastName);

            // Aliases : <akaList><aka><lastName>…</lastName><firstName>…</firstName></aka></akaList>
            NodeList akaList = entry.getElementsByTagName("aka");
            List<String> aliases = new ArrayList<>();
            for (int j = 0; j < akaList.getLength(); j++) {
                Element aka      = (Element) akaList.item(j);
                String akaLast  = textOf(aka, "lastName");
                String akaFirst = textOf(aka, "firstName");
                if (akaLast != null && !akaLast.isBlank()) {
                    aliases.add(buildName(akaFirst, akaLast));
                }
            }

            // Pays : <addressList><address><country>Iran</country></address></addressList>
            // OFAC utilise des noms complets, pas des codes — on stocke null (pas de table de correspondance)
            result.add(SanctionsEntry.builder()
                .listName("OFAC_SDN")
                .entityName(truncate(entityName, 255))
                .aliases(aliases.isEmpty() ? null : truncate(String.join(", ", aliases), 5000))
                .countryCode(null) // OFAC fournit des noms de pays, pas des codes ISO
                .entityType(mapOfacType(sdnType))
                .isActive(true)
                .build());
        }

        return result;
    }

    /**
     * Parse la liste consolidée des Nations Unies.
     * Source : {@code https://scsanctions.un.org/resources/xml/en/consolidated.xml}
     *
     * <p>Structure XML :
     * <pre>
     * &lt;CONSOLIDATED_LIST&gt;
     *   &lt;INDIVIDUALS&gt;&lt;INDIVIDUAL&gt;
     *     &lt;FIRST_NAME&gt;…&lt;/FIRST_NAME&gt;
     *     &lt;SECOND_NAME&gt;…&lt;/SECOND_NAME&gt;
     *     &lt;INDIVIDUAL_ALIAS&gt;&lt;ALIAS_NAME&gt;…&lt;/ALIAS_NAME&gt;&lt;/INDIVIDUAL_ALIAS&gt;
     *     &lt;INDIVIDUAL_ADDRESS&gt;&lt;COUNTRY_ID&gt;AF&lt;/COUNTRY_ID&gt;&lt;/INDIVIDUAL_ADDRESS&gt;
     *   &lt;/INDIVIDUAL&gt;&lt;/INDIVIDUALS&gt;
     *   &lt;ENTITIES&gt;&lt;ENTITY&gt;
     *     &lt;FIRST_NAME&gt;…&lt;/FIRST_NAME&gt;
     *     &lt;ENTITY_ALIAS&gt;&lt;ALIAS_NAME&gt;…&lt;/ALIAS_NAME&gt;&lt;/ENTITY_ALIAS&gt;
     *   &lt;/ENTITY&gt;&lt;/ENTITIES&gt;
     * &lt;/CONSOLIDATED_LIST&gt;
     * </pre>
     */
    List<SanctionsEntry> parseUn(InputStream content) throws Exception {
        Document doc = parseXml(content);
        List<SanctionsEntry> result = new ArrayList<>();
        result.addAll(parseUnSection(doc, "INDIVIDUAL", "INDIVIDUAL_ALIAS",  "INDIVIDUAL_ADDRESS"));
        result.addAll(parseUnSection(doc, "ENTITY",     "ENTITY_ALIAS",      "ENTITY_ADDRESS"));
        return result;
    }

    private List<SanctionsEntry> parseUnSection(Document doc, String tag,
                                                 String aliasTag, String addrTag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        List<SanctionsEntry> result = new ArrayList<>(nodes.getLength());

        for (int i = 0; i < nodes.getLength(); i++) {
            Element node   = (Element) nodes.item(i);
            String first   = textOf(node, "FIRST_NAME");
            String second  = textOf(node, "SECOND_NAME");
            String third   = textOf(node, "THIRD_NAME");
            if (first == null || first.isBlank()) continue;

            // Construire le nom complet à partir des segments
            StringBuilder sb = new StringBuilder(first.trim());
            if (second != null && !second.isBlank()) sb.append(" ").append(second.trim());
            if (third  != null && !third.isBlank())  sb.append(" ").append(third.trim());
            String entityName = sb.toString().trim();

            // Aliases
            NodeList aliasList = node.getElementsByTagName(aliasTag);
            List<String> aliases = new ArrayList<>();
            for (int j = 0; j < aliasList.getLength(); j++) {
                String aliasName = textOf((Element) aliasList.item(j), "ALIAS_NAME");
                if (aliasName != null && !aliasName.isBlank()) aliases.add(aliasName.trim());
            }

            // Code pays ISO dans l'adresse
            String countryCode = null;
            NodeList addrs = node.getElementsByTagName(addrTag);
            if (addrs.getLength() > 0) {
                String raw = textOf((Element) addrs.item(0), "COUNTRY_ID");
                if (raw != null && raw.length() == 2) countryCode = raw.toUpperCase();
            }

            result.add(SanctionsEntry.builder()
                .listName("UN_CONSOLIDATED")
                .entityName(truncate(entityName, 255))
                .aliases(aliases.isEmpty() ? null : truncate(String.join(", ", aliases), 5000))
                .countryCode(countryCode)
                .entityType(tag)
                .isActive(true)
                .build());
        }
        return result;
    }

    /**
     * Parse la liste consolidée de l'Union Européenne.
     * Source : {@code https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content}
     *
     * <p>Structure XML :
     * <pre>
     * &lt;export&gt;
     *   &lt;sanctionEntity&gt;
     *     &lt;nameAliasList&gt;
     *       &lt;nameAlias wholeName="NOM COMPLET" strong="true"/&gt;
     *       &lt;nameAlias wholeName="ALIAS" strong="false"/&gt;
     *     &lt;/nameAliasList&gt;
     *     &lt;addressList&gt;
     *       &lt;address countryDescription="France" countryIso2Code="FR"/&gt;
     *     &lt;/addressList&gt;
     *   &lt;/sanctionEntity&gt;
     * &lt;/export&gt;
     * </pre>
     */
    List<SanctionsEntry> parseEu(InputStream content) throws Exception {
        Document doc = parseXml(content);
        NodeList entities = doc.getElementsByTagName("sanctionEntity");
        List<SanctionsEntry> result = new ArrayList<>(entities.getLength());

        for (int i = 0; i < entities.getLength(); i++) {
            Element entity = (Element) entities.item(i);

            // Noms : le premier nameAlias (ou celui avec strong="true") est le nom principal
            NodeList nameAliases = entity.getElementsByTagName("nameAlias");
            if (nameAliases.getLength() == 0) continue;

            String entityName = null;
            List<String> aliases = new ArrayList<>();

            for (int j = 0; j < nameAliases.getLength(); j++) {
                Element na        = (Element) nameAliases.item(j);
                String wholeName  = na.getAttribute("wholeName");
                String strong     = na.getAttribute("strong");
                if (wholeName == null || wholeName.isBlank()) continue;

                if (entityName == null || "true".equalsIgnoreCase(strong)) {
                    if (entityName != null) aliases.add(0, entityName); // l'ancien principal devient alias
                    entityName = wholeName.trim();
                } else {
                    aliases.add(wholeName.trim());
                }
            }
            if (entityName == null) continue;

            // Code pays
            String countryCode = null;
            NodeList addresses = entity.getElementsByTagName("address");
            if (addresses.getLength() > 0) {
                String iso2 = ((Element) addresses.item(0)).getAttribute("countryIso2Code");
                if (iso2 != null && iso2.length() == 2) countryCode = iso2.toUpperCase();
            }

            result.add(SanctionsEntry.builder()
                .listName("EU_CONSOLIDATED")
                .entityName(truncate(entityName, 255))
                .aliases(aliases.isEmpty() ? null : truncate(String.join(", ", aliases), 5000))
                .countryCode(countryCode)
                .entityType(null) // EU ne distingue pas toujours individu/entité
                .isActive(true)
                .build());
        }
        return result;
    }

    // ── Parseur CSV ───────────────────────────────────────────────────────────

    /**
     * Parse un fichier CSV pour une liste manuelle (ECOWAS_LOCAL, CUSTOM, ou toute autre liste).
     *
     * <p>Format (sans entête, une entité par ligne) :
     * <pre>
     *   entityName,aliases,countryCode,entityType
     * </pre>
     * Exemple :
     * <pre>
     *   # Commentaire ignoré
     *   "KONAN AMARA","AMARA KONAN|K. AMARA",CI,INDIVIDUAL
     *   "SOCIÉTÉ FANTÔME",,NG,ENTITY
     * </pre>
     */
    List<SanctionsEntry> parseCsv(String listName, String csvContent) {
        List<SanctionsEntry> result = new ArrayList<>();
        String[] lines = csvContent.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            // Découpage simple en 4 champs (guillemets éventuels retirés)
            String[] fields = trimmed.split(",", 4);
            if (fields.length < 1) continue;

            String entityName = stripQuotes(fields[0]);
            if (entityName.isBlank()) continue;

            String rawAliases = fields.length > 1 ? stripQuotes(fields[1]) : null;
            String countryRaw = fields.length > 2 ? stripQuotes(fields[2]) : null;
            String entityType = fields.length > 3 ? stripQuotes(fields[3]) : null;

            // Aliases : séparateur "|" dans le champ
            String aliases = null;
            if (rawAliases != null && !rawAliases.isBlank()) {
                aliases = rawAliases.replace("|", ", ");
            }

            // Code pays : doit être exactement 2 lettres
            String countryCode = (countryRaw != null && countryRaw.length() == 2)
                ? countryRaw.toUpperCase() : null;

            // Type d'entité : normalisation en majuscules
            String type = (entityType != null && !entityType.isBlank())
                ? entityType.trim().toUpperCase() : null;

            result.add(SanctionsEntry.builder()
                .listName(listName)
                .entityName(truncate(entityName, 255))
                .aliases(aliases != null ? truncate(aliases, 5000) : null)
                .countryCode(countryCode)
                .entityType(type)
                .isActive(true)
                .build());
        }

        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveUrl(String listName) {
        return switch (listName) {
            case "OFAC_SDN"        -> ofacUrl;
            case "UN_CONSOLIDATED" -> unUrl;
            case "EU_CONSOLIDATED" -> euUrl;
            default -> throw new IllegalArgumentException(
                "Pas d'URL configurée pour la liste : " + listName);
        };
    }

    /** Construit un DOM en désactivant les entités externes (protection XXE). */
    private Document parseXml(InputStream content) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(content);
    }

    private String textOf(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        String text = nodes.item(0).getTextContent();
        return (text != null) ? text.trim() : null;
    }

    private String buildName(String first, String last) {
        if (first != null && !first.isBlank()) {
            return (first.trim() + " " + last.trim()).trim();
        }
        return last.trim();
    }

    private String mapOfacType(String sdnType) {
        if (sdnType == null) return null;
        return switch (sdnType.trim()) {
            case "Individual" -> "INDIVIDUAL";
            case "Entity"     -> "ENTITY";
            case "Vessel"     -> "VESSEL";
            case "Aircraft"   -> "AIRCRAFT";
            default           -> null;
        };
    }

    private String stripQuotes(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("^\"|\"$", "").trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
