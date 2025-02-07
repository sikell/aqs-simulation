package de.sikeller.aqs.model;

import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;

import java.util.ArrayList;
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
        this.algorithm = algorithm;
    }

    public ArrayList<Class<?>> getAllAlgorithms() {
        ArrayList<Class<?>> algorithmList;
        Reflections reflections =
                new Reflections(
                        "de.sikeller.aqs.taxi.algorithm", new SubTypesScanner(false));
        Set<Class <?>> allClasses = reflections.getSubTypesOf(Object.class);
        algorithmList = new ArrayList<>();
        allClasses.forEach(
                aClass -> {
                    if (aClass.getName().contains("model")) {
                        return;
                    }
                    int mod = aClass.getModifiers();
                    if (mod == 1) {
                        algorithmList.add(aClass);
                    }

                });
        return algorithmList;
    }

}
