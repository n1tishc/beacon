package dev.beacon.auth.token;

import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public signing key so the gateway and services can verify tokens. */
@RestController
class JwksController {

    private final Map<String, Object> publicJwks;

    JwksController(RSAKey signingKey) {
        this.publicJwks = new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return publicJwks;
    }
}
