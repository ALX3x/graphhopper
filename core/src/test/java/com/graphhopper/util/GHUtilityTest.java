/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.util;

import com.graphhopper.coll.GHIntLongHashMap;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import org.junit.jupiter.api.Test;
import com.github.javafaker.Faker;
import java.util.Random; 

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Peter Karich
 */

public class GHUtilityTest {

    @Test
    public void testEdgeStuff() {
        assertEquals(2, GHUtility.createEdgeKey(1, false));
        assertEquals(3, GHUtility.createEdgeKey(1, true));
    }

    @Test
    public void testZeroValue() {
        GHIntLongHashMap map1 = new GHIntLongHashMap();
        assertFalse(map1.containsKey(0));
        map1.put(0, 3);
        map1.put(1, 0);
        map1.put(2, 1);
        assertTrue(map1.containsKey(0));
        assertEquals(3, map1.get(0));
        assertEquals(0, map1.get(1));
        assertEquals(1, map1.get(2));
    }

    // --- tests pour GHUtility.getProblems(Graph) ---

    private static BaseGraph newEmptyGraph() {
        BaseGraph g = new BaseGraph.Builder(64)
                .withTurnCosts(false)   // remove if not available in your snapshot
                .build();
        g.create(16);
        return g;
    }

    /**
     * Helper: distance minimale entre deux nœuds adjacents (arêtes multiples possibles).
     * Signature principale sur l'interface Graph.
     */
    private static double getMinDist(Graph graph, int p, int q) {
        EdgeExplorer explorer = graph.createEdgeExplorer();
        EdgeIterator iter = explorer.setBaseNode(p);
        double distance = Double.MAX_VALUE;
        while (iter.next()) {
            if (iter.getAdjNode() == q) {
                distance = Math.min(distance, iter.getDistance());
            }
        }
        return distance;
    }

    /** Surcharge pratique pour éviter les casts lorsque on a un BaseGraph. */
    private static double getMinDist(BaseGraph graph, int p, int q) {
        return getMinDist((Graph) graph, p, q);
    }

    /** Helper: échouer explicitement avec un message. */
    private static void fail(String message) {
        throw new AssertionError(message);
    }

    /**
     * Ce test échoue si de nouvelles validations apparaissent et rejettent des entrées valides,
     * ou si l’exploration des arêtes/nœuds est brisée.
     * Given :- 2 nœuds valides (Paris, Londres) - 1 arête bidirectionnelle 0↔1 (distance arbitraire)
     * When : GHUtility.getProblems(g) est exécuté
     * Then :La liste retournée est vide.
     */
    @Test
    public void getProblems_validGraph_returnsEmptyList() {
        // Définition de 2 nœuds avec des coordonnées valides
        BaseGraph g = newEmptyGraph();
        NodeAccess na = g.getNodeAccess();

        na.setNode(0, 48.8566, 2.3522);     // Paris    
        na.setNode(1, 51.5074, -0.1278);    // Londre 
        // Ajout d'une arête bidirectionnelle entre 0 et 1 (distance arbitraire en mètres)
        g.edge(0, 1).setDistance(343_000);

        List<String> problems = GHUtility.getProblems(g);
        assertNotNull(problems);
        assertTrue(problems.isEmpty(), "Aucun problème attendu pour un graphe valide");
    }

    /**
     * - Sécurise la qualité des données géospatiales avant tout calcul de routing.
     *  - Empêche que des lat/lon aberrantes se propagent silencieusement dans le système.
     * Cas d'erreurs de coordonnées :
     * - latitude > 90 (hors bornes)
     * - longitude > 180 (hors bornes)
     * On s'attend à voir les deux messages correspondants dans la liste.
     */
    @Test
    public void getProblems_outOfRangeLatLon_reportsMessages() {
        BaseGraph g = newEmptyGraph();
        NodeAccess na = g.getNodeAccess();

        // 2 nœuds volontairement hors bornes pour déclencher des messages d'erreur
        na.setNode(0, 123.0, 0.0);   // latitude > 90
        na.setNode(1, 0.0, 200.0);   // longitude > 180

        List<String> problems = GHUtility.getProblems(g);

        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(s -> s.contains("latitude is not within its bounds")));
        assertTrue(problems.stream().anyMatch(s -> s.contains("longitude is not within its bounds")));
    }

    // -- Tests pour getMinDist() ---

    /**
     * Mesure directe avec arête unique.
     * - Régression sur l’itération des arêtes ou sur les champs de distance.
     * Given  : une arête unique 0↔1 de distance 100
     * When   : on calcule getMinDist(0,1) et getMinDist(1,0)
     * Then   : on obtient 100 dans les deux sens (arête non orientée)
     */
    @Test
    public void getMinDist_singleEdge_returnsDistance() {
        BaseGraph g = newEmptyGraph();
        NodeAccess na = g.getNodeAccess();

        na.setNode(0, 45.0, 0.0);
        na.setNode(1, 46.0, 1.0);
        g.edge(0, 1).setDistance(100);

        double d01 = getMinDist(g, 0, 1);
        assertEquals(100.0, d01, 1e-6);

        double d10 = getMinDist(g, 1, 0);
        assertEquals(100.0, d10, 1e-6);
    }

    /**
     * - Quand plusieurs segments géométriques distincts existent entre deux nœuds (ex. voies
     *    alternatives avec géométries différentes), la lecture doit prendre la meilleure valeur.
     * Given  : deux arêtes parallèles 0↔1 avec distances 250 et 80
     * When   : on calcule getMinDist(0,1)
     * Then   : on obtient la plus petite distance, soit 80
     */
    @Test
    public void getMinDist_parallelEdges_returnsMinimum() {
        BaseGraph g = newEmptyGraph();
        NodeAccess na = g.getNodeAccess();

        na.setNode(0, 45.0, 0.0);
        na.setNode(1, 46.0, 1.0);

        // Deux arêtes parallèles entre 0 et 1, distances différentes
        g.edge(0, 1).setDistance(250);
        g.edge(0, 1).setDistance(80);

        double d = getMinDist(g, 0, 1);
        assertEquals(80.0, d, 1e-6, "Parmi les arêtes parallèles, on retourne la plus petite distance");
    }

    /**
     * - Ne pas confondre « accessible via un 3e nœud » et « adjacent directement »
     * Given  : 0 est connecté à 2 (30), 1 est connecté à 2 (40), mais PAS d’arête 0↔1
     * When   : on calcule getMinDist(0,1)
     * Then   : pas d’arête directe ⇒ Double.MAX_VALUE
     */
    @Test
    public void getMinDist_ignoresOtherAdjNodes() {
        BaseGraph g = newEmptyGraph();
        NodeAccess na = g.getNodeAccess();

        na.setNode(0, 45.0, 0.0);
        na.setNode(1, 46.0, 1.0);
        na.setNode(2, 47.0, 2.0);

        // 0 connecté seulement à 2 ; pas d’arête directe 0-1
        g.edge(0, 2).setDistance(30);
        // 1 a éventuellement une autre arête, mais pas avec 0
        g.edge(1, 2).setDistance(40);

        double d = getMinDist(g, 0, 1);
        assertEquals(Double.MAX_VALUE, d, "Sans arête directe 0-1, on doit retourner Double.MAX_VALUE");
    }

    /**
     * Vérifie le comportement par défaut sans “fuite” de valeurs arbitraires.
     * Given  : deux nœuds 0 et 1 sans aucune arête
     * When   : on calcule getMinDist(0,1)
     * Then   : Double.MAX_VALUE est renvoyé
     */
    @Test
    public void getMinDist_noEdge_returnsMaxValue() {
        BaseGraph g = newEmptyGraph();
        NodeAccess na = g.getNodeAccess();

        na.setNode(0, 45.0, 0.0);
        na.setNode(1, 46.0, 1.0);

        // aucune arête du tout
        double d = getMinDist(g, 0, 1);
        assertEquals(Double.MAX_VALUE, d, "Sans arête, renvoyer Double.MAX_VALUE");
    }

    /**
     *  Vérifie que le helper fail(message) lève un AssertionError avec le message fourni.
     * Given  : un message d’erreur "boom"
     * When   : on appelle fail("boom")
     * Then   : un AssertionError est lancé avec ce message
     */
    @Test
    public void fail_throwsAssertionError_withMessage() {
        AssertionError err = assertThrows(AssertionError.class, () -> fail("boom"));
        assertEquals("boom", err.getMessage(), "fail(message) doit relancer un AssertionError avec le message passé");
    }
    /**
 * Génération pseudo-aléatoire (déterministe) de coordonnées avec JavaFaker.
 * But :
 *  - Vérifier que pour un petit graphe dont les nœuds ont des lat/lon réalistes, aucune
 *    alerte n’est remontée par GHUtility.getProblems.
 *  - Utiliser JavaFaker pour rapprocher le test de données "du monde réel", tout en
 *    restant reproductible (seed fixée).
 * Détails :
 *  - Faker.address().latitude()/longitude() renvoient des chaînes dans les bornes [-90,90], [-180,180].
 *  - On parse en double et on pose 5 nœuds. On connecte une petite chaîne d’arêtes.
 *  - Seed = 12345 pour la reproductibilité.
 */
    @Test
    public void getProblems_randomValidLatLon_withFaker_returnsEmpty() {
        BaseGraph g = newEmptyGraph();
        NodeAccess na = g.getNodeAccess();
        Faker faker = new Faker(new Random(12345)); // seed fixe → test stable

        final int N = 5;
        for (int i = 0; i < N; i++) {
            double lat = Double.parseDouble(faker.address().latitude());
            double lon = Double.parseDouble(faker.address().longitude());
            na.setNode(i, lat, lon);
        }
        for (int i = 0; i < N - 1; i++) g.edge(i, i + 1).setDistance(100 + i * 10);

        List<String> problems = GHUtility.getProblems(g);
        assertNotNull(problems);
        assertTrue(problems.isEmpty(), "Aucun problème attendu avec des coordonnées Faker valides");
    }


}
