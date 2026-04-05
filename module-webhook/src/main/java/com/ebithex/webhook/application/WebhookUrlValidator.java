package com.ebithex.webhook.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Protection SSRF (Server-Side Request Forgery) pour les URLs webhook marchands.
 *
 * <p>Rejette :
 * <ul>
 *   <li>Schéma non-HTTPS (file://, ftp://, javascript:, etc.)</li>
 *   <li>Noms d'hôtes internes (localhost, *.local, *.internal)</li>
 *   <li>Ports non autorisés — seuls 443 et 8443 sont acceptés pour HTTPS</li>
 *   <li>IPs privées RFC-1918 : 10.x, 172.16–31.x, 192.168.x</li>
 *   <li>Loopback IPv4/IPv6 : 127.x, ::1</li>
 *   <li>Link-local / APIPA : 169.254.x (metadata cloud AWS/GCP/Azure)</li>
 *   <li>Plages privées IPv6 ULA : fc00::/7 (fc00:: – fdff::)</li>
 *   <li>Adresses multicast / non routables (0.0.0.0, 224.x–239.x)</li>
 * </ul>
 *
 * <p>Note : la résolution DNS au moment de l'enregistrement est une vérification
 * best-effort. Si l'hôte ne se résout pas, l'URL est acceptée (la livraison échouera
 * lors de l'envoi effectif). La vérification SSRF est ré-effectuée à chaque livraison.
 */
@Component
@Slf4j
public class WebhookUrlValidator {

    /** Ports HTTPS autorisés. */
    private static final Set<Integer> ALLOWED_HTTPS_PORTS = Set.of(443, 8443);

    /** Noms d'hôtes internes bloqués (insensible à la casse). */
    private static final List<Pattern> BLOCKED_HOSTNAME_PATTERNS = List.of(
        Pattern.compile("^localhost$",       Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\.local$",       Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\.internal$",    Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\.localdomain$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^metadata\\..*",    Pattern.CASE_INSENSITIVE)  // ex : metadata.google.internal
    );

    /**
     * Valide une URL webhook marchande contre les vecteurs SSRF connus.
     *
     * @param rawUrl URL à valider (non nulle)
     * @throws IllegalArgumentException si l'URL est rejetée, avec message explicatif
     */
    public void validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("URL webhook vide");
        }

        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL webhook invalide : " + e.getMessage());
        }

        // 1. Schéma HTTPS obligatoire
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                "URL webhook doit utiliser HTTPS (schéma reçu : '" + scheme + "')");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL webhook sans hôte");
        }

        // 2. Vérifier les noms d'hôtes internes bloqués
        for (Pattern blocked : BLOCKED_HOSTNAME_PATTERNS) {
            if (blocked.matcher(host).matches()) {
                throw new IllegalArgumentException(
                    "URL webhook pointe vers un hôte interne non autorisé : " + host);
            }
        }

        // 3. Valider le port (uniquement 443 ou 8443 pour HTTPS ; -1 = absent = 443 par défaut)
        int port = uri.getPort();
        if (port != -1 && !ALLOWED_HTTPS_PORTS.contains(port)) {
            throw new IllegalArgumentException(
                "Port non autorisé pour un webhook HTTPS : " + port
                + " (ports acceptés : " + ALLOWED_HTTPS_PORTS + ")");
        }

        // 4. Résoudre l'IP et vérifier les plages privées/non-routables
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            // Hôte non résolu à l'enregistrement : acceptable — la livraison échouera
            // si l'hôte reste invalide. Pas de blocage préventif sur erreur DNS.
            log.warn("URL webhook : hôte '{}' non résolu à l'enregistrement (webhook accepté)", host);
            return;
        }

        if (isBlockedAddress(address)) {
            throw new IllegalArgumentException(
                "URL webhook pointe vers une adresse IP non routable : " + address.getHostAddress());
        }

        log.debug("URL webhook validée : {}", rawUrl);
    }

    /**
     * Détermine si une adresse IP est dans une plage privée, loopback,
     * link-local, multicast, ou ULA IPv6.
     */
    private boolean isBlockedAddress(InetAddress addr) {
        if (addr.isLoopbackAddress()  // 127.x.x.x / ::1
         || addr.isSiteLocalAddress() // 10.x, 172.16-31.x, 192.168.x
         || addr.isLinkLocalAddress() // 169.254.x (APIPA/metadata) / fe80::/10
         || addr.isAnyLocalAddress()  // 0.0.0.0 / ::
         || addr.isMulticastAddress()) {
            return true;
        }
        // IPv6 ULA (fc00::/7 = fc00:: à fdff::) — non couvert par isSiteLocalAddress()
        if (addr instanceof Inet6Address) {
            byte[] bytes = addr.getAddress();
            int firstByte = bytes[0] & 0xFF;
            if (firstByte >= 0xFC && firstByte <= 0xFD) {
                return true; // fc00::/7
            }
        }
        return false;
    }
}
