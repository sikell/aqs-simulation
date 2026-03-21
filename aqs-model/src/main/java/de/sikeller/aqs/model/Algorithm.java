package de.sikeller.aqs.model;

import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.lang.reflect.Modifier;
import java.util.Set;


public class Algorithm {
    private TaxiAlgorithm algorithm;

    public Algorithm(TaxiAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public TaxiAlgorithm get() {
        return algorithm;
    }

    public void setAlgorithm(TaxiAlgorithm algorithm) {
        if (this.algorithm != null && this.algorithm != algorithm) {
            this.algorithm.shutdown();
        }
        this.algorithm = algorithm;
    }

    public ArrayList<Class<?>> getAllAlgorithms() {
        ArrayList<Class<?>> algorithmList = new ArrayList<>();
        Reflections reflections =
                new Reflections(
                        "de.sikeller.aqs", new SubTypesScanner(false));
        Set<Class<? extends TaxiAlgorithm>> allAlgorithms = reflections.getSubTypesOf(TaxiAlgorithm.class);
        allAlgorithms.forEach(aClass -> {
            int mod = aClass.getModifiers();
            if (Modifier.isPublic(mod) && !Modifier.isAbstract(mod) && !aClass.isInterface()) {
                algorithmList.add(aClass);
            }
        });

        algorithmList.sort(Comparator.comparing(Class::getSimpleName));
        return algorithmList;
    }

}
