/**
 * Module paiement Ebithex.
 *
 * Le filtre Hibernate {@code merchantFilter} est défini ici une seule fois
 * pour être partagé par toutes les entités du module (Transaction, Payout…).
 * Chaque entité utilise {@code @Filter(name = "merchantFilter")} sans redéfinir
 * le {@code @FilterDef}.
 */
@FilterDef(
    name = "merchantFilter",
    parameters = @ParamDef(name = "merchantId", type = String.class)
)
package com.ebithex.payment;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
