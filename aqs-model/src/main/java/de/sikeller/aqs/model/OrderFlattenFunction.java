package de.sikeller.aqs.model;

import java.util.List;
import java.util.function.Function;

public interface OrderFlattenFunction extends Function<List<Order>, List<OrderNode>> {}
