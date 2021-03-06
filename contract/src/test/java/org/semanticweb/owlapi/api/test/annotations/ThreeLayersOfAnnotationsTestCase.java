package org.semanticweb.owlapi.api.test.annotations;

import org.semanticweb.owlapi.api.test.baseclasses.AbstractRoundTrippingTestCase;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLRuntimeException;
import org.semanticweb.owlapi.vocab.OWL2Datatype;

@SuppressWarnings("javadoc")
public class ThreeLayersOfAnnotationsTestCase extends AbstractRoundTrippingTestCase {
    private static final String oboInOwl = "urn:obo:";

    @Override
    protected OWLOntology createOntology() {
        OWLOntology o;
        try {
            o = m.createOntology(IRI.create("urn:nested:", "ontology"));
        } catch (OWLOntologyCreationException e) {
            throw new OWLRuntimeException(e);
        }
        OWLClass dbxref = df.getOWLClass(IRI.create(oboInOwl, "DbXref"));
        OWLClass definition = df.getOWLClass(IRI.create(oboInOwl, "Definition"));
        OWLObjectProperty adjacent_to =
            df.getOWLObjectProperty(IRI.create(oboInOwl, "adjacent_to"));
        OWLAnnotationProperty hasDefinition =
            df.getOWLAnnotationProperty(IRI.create(oboInOwl, "hasDefinition"));
        OWLAnnotationProperty hasdbxref =
            df.getOWLAnnotationProperty(IRI.create(oboInOwl, "hasDbXref"));
        OWLDataProperty hasuri = df.getOWLDataProperty(IRI.create(oboInOwl, "hasURI"));
        OWLAnonymousIndividual ind1 = df.getOWLAnonymousIndividual();
        m.addAxiom(o, df.getOWLClassAssertionAxiom(dbxref, ind1));
        m.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(hasuri, ind1,
            df.getOWLLiteral("urn:SO:SO_ke", OWL2Datatype.XSD_ANY_URI)));
        OWLAnonymousIndividual ind2 = df.getOWLAnonymousIndividual();
        m.addAxiom(o, df.getOWLClassAssertionAxiom(definition, ind2));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(hasdbxref, ind2, ind1));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(hasDefinition, adjacent_to.getIRI(), ind2));
        return o;
    }

    @Override
    public void testManchesterOWLSyntax() {
        // not supported in Manchester syntax
    }
}
