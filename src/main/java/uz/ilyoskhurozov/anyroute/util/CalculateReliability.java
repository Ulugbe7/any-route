package uz.ilyoskhurozov.anyroute.util;

import java.util.*;

public class CalculateReliability {

    public static double inModeVirtualChannel(Map<String, Double> routersRel, Map<String, Map<String, Double>> conRelTable, List<String> route){
        double k = 1;

        Iterator<String> routeIter = route.iterator();

        String prev, next = routeIter.next();
        k *= routersRel.get(next);

        while (routeIter.hasNext()){
            prev = next;
            next = routeIter.next();

            k *= conRelTable.get(prev).get(next);
            k *= routersRel.get(next);
        }

        return k;
    }

    public static double inModeDatagram(Map<String, Double> routersRel, Map<String, Map<String, Double>> conRelTable, String begin, String end){
        List<String> routes = new ArrayList<>();
        findAllPaths(conRelTable, begin+";-;", begin, begin, end, routes);

        double mid = 1;
        int k = 0;
        for (String route : routes) {
            String[] arr = route.split(";");

            double sub = 1;

            for(int i = 1; i < arr.length-1; i++){
                if (arr[i].equals("-")) {
                    sub *= conRelTable.get(arr[i-1]).get(arr[i+1]);
                } else {
                    sub *= routersRel.get(arr[i]);
                }
            }
            mid *= 1-sub;
        }

        return routersRel.get(begin) * (1 - mid) * routersRel.get(end);
    }

    private static void findAllPaths(Map<String, Map<String, Double>> conRelTable, String route, String beginR, String curR, String endR, List<String> routes){
        conRelTable.get(curR).forEach((r, rel) -> {
            if (rel > 0 && !route.contains(r + ";") && !r.equals(beginR)) {
                if (r.equals(endR)) {
                    routes.add(route+endR);
                } else {
                    findAllPaths(conRelTable, route+r+";-;", beginR, r, endR, routes);
                }
            }
        });
    }
}
