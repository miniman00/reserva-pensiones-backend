package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.model.Pension;
import uy.pensiones.repo.OwnerAnalyticsQueryRepository;
import uy.pensiones.security.Authz;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class OwnerAnalyticsService {
    private final OwnerEntitlementService entitlements;
    private final PensionService pensions;
    private final Authz authz;
    private final OwnerAnalyticsQueryRepository analytics;

    public OwnerAnalyticsService(OwnerEntitlementService entitlements, PensionService pensions,
                                 Authz authz, OwnerAnalyticsQueryRepository analytics) {
        this.entitlements = entitlements;
        this.pensions = pensions;
        this.authz = authz;
        this.analytics = analytics;
    }

    @Transactional(readOnly = true)
    public PensionAnalytics pension(Long userId, Long pensionId, int days) {
        entitlements.requireAdvancedAnalytics(userId);
        Pension owned = ownedPensions(userId).stream().filter(p -> p.getId().equals(pensionId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));
        Range range = range(days);
        Map<Key, MutableDaily> data = load(List.of(owned.getId()), range);
        List<DailyMetric> daily = dailyFor(owned.getId(), range, data);
        return new PensionAnalytics(range, ref(owned), summary(daily), daily);
    }

    @Transactional(readOnly = true)
    public PortfolioAnalytics portfolio(Long userId, int days) {
        entitlements.requireConsolidatedAnalytics(userId);
        List<Pension> owned = ownedPensions(userId);
        Range range = range(days);
        List<Long> ids = owned.stream().map(Pension::getId).toList();
        Map<Key, MutableDaily> data = load(ids, range);
        List<PensionSummary> pensionRows = owned.stream().map(p -> {
            List<DailyMetric> daily = dailyFor(p.getId(), range, data);
            return new PensionSummary(ref(p), summary(daily));
        }).sorted(Comparator.comparingLong((PensionSummary row) -> row.summary().inquiries()).reversed()
                .thenComparing(row -> row.pension().name(), String.CASE_INSENSITIVE_ORDER)).toList();
        List<DailyMetric> totalDaily = aggregateDaily(ids, range, data);
        return new PortfolioAnalytics(range, summary(totalDaily), pensionRows, totalDaily);
    }

    @Transactional(readOnly = true)
    public ExportFile export(Long userId, int days) {
        entitlements.requireExportEnabled(userId);
        List<Pension> owned = ownedPensions(userId);
        Range range = range(days);
        List<Long> ids = owned.stream().map(Pension::getId).toList();
        Map<Key, MutableDaily> data = load(ids, range);
        StringBuilder csv = new StringBuilder("date,pension_id,pension_name,views,favorites,inquiries,conversions,inquiry_rate_pct,conversion_rate_pct\n");
        for (Pension pension : owned) {
            for (DailyMetric day : dailyFor(pension.getId(), range, data)) {
                csv.append(day.date()).append(',').append(pension.getId()).append(',').append(csv(pension.getName())).append(',')
                        .append(day.views()).append(',').append(day.favorites()).append(',').append(day.inquiries()).append(',')
                        .append(day.conversions()).append(',').append(day.inquiryRate()).append(',').append(day.conversionRate()).append('\n');
            }
        }
        String filename = "analitica-pensiones-" + range.from() + "-" + range.to() + ".csv";
        return new ExportFile(filename, csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<Pension> ownedPensions(Long userId) {
        return pensions.myPensions(userId).stream().filter(p -> authz.isOwner(userId, p.getId())).toList();
    }

    private Range range(int requested) {
        int days = requested == 7 || requested == 90 ? requested : 30;
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        return new Range(days, to.minusDays(days - 1L), to);
    }

    private Map<Key, MutableDaily> load(List<Long> ids, Range range) {
        Map<Key, MutableDaily> data = new HashMap<>();
        analytics.views(ids, range.from(), range.to()).forEach(r -> cell(data, id(r[0]), date(r[1])).views += number(r[2]));
        analytics.favorites(ids, range.from(), range.to()).forEach(r -> cell(data, id(r[0]), date(r[1])).favorites += number(r[2]));
        analytics.inquiries(ids, range.from(), range.to()).forEach(r -> {
            MutableDaily c = cell(data, id(r[0]), date(r[1]));
            c.inquiries += number(r[2]); c.conversions += number(r[3]);
        });
        return data;
    }

    private List<DailyMetric> dailyFor(Long id, Range range, Map<Key, MutableDaily> data) {
        List<DailyMetric> out = new ArrayList<>();
        for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
            MutableDaily c = data.getOrDefault(new Key(id, day), new MutableDaily());
            out.add(metric(day, c.views, c.favorites, c.inquiries, c.conversions));
        }
        return List.copyOf(out);
    }

    private List<DailyMetric> aggregateDaily(List<Long> ids, Range range, Map<Key, MutableDaily> data) {
        List<DailyMetric> out = new ArrayList<>();
        for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
            long views=0,favorites=0,inquiries=0,conversions=0;
            for (Long id : ids) { MutableDaily c=data.get(new Key(id,day)); if(c!=null){views+=c.views;favorites+=c.favorites;inquiries+=c.inquiries;conversions+=c.conversions;} }
            out.add(metric(day,views,favorites,inquiries,conversions));
        }
        return List.copyOf(out);
    }

    private Summary summary(List<DailyMetric> daily) {
        long views=0,favorites=0,inquiries=0,conversions=0;
        for (DailyMetric d:daily){views+=d.views();favorites+=d.favorites();inquiries+=d.inquiries();conversions+=d.conversions();}
        return new Summary(views,favorites,inquiries,conversions,percent(inquiries,views),percent(conversions,inquiries));
    }
    private DailyMetric metric(LocalDate day,long views,long favorites,long inquiries,long conversions){return new DailyMetric(day,views,favorites,inquiries,conversions,percent(inquiries,views),percent(conversions,inquiries));}
    private double percent(long a,long b){return b<=0?0d:Math.round(a*10000d/b)/100d;}
    private MutableDaily cell(Map<Key, MutableDaily> map,Long id,LocalDate day){return map.computeIfAbsent(new Key(id,day),k->new MutableDaily());}
    private Long id(Object v){return ((Number)v).longValue();}
    private long number(Object v){return v==null?0L:((Number)v).longValue();}
    private LocalDate date(Object v){if(v instanceof LocalDate d)return d;if(v instanceof java.sql.Date d)return d.toLocalDate();return LocalDate.parse(String.valueOf(v));}
    private PensionRef ref(Pension p){return new PensionRef(p.getId(),p.getName(),p.getCity(),p.getState());}
    private String csv(String value){String s=value==null?"":value;return '"'+s.replace("\"","\"\"")+'"';}

    private record Key(Long pensionId, LocalDate date) {}
    private static class MutableDaily { long views,favorites,inquiries,conversions; }
    public record Range(int days, LocalDate from, LocalDate to) {}
    public record PensionRef(Long id,String name,String city,String state) {}
    public record Summary(long views,long favorites,long inquiries,long conversions,double inquiryRate,double conversionRate) {}
    public record DailyMetric(LocalDate date,long views,long favorites,long inquiries,long conversions,double inquiryRate,double conversionRate) {}
    public record PensionAnalytics(Range range,PensionRef pension,Summary summary,List<DailyMetric> daily) {}
    public record PensionSummary(PensionRef pension,Summary summary) {}
    public record PortfolioAnalytics(Range range,Summary summary,List<PensionSummary> pensions,List<DailyMetric> daily) {}
    public record ExportFile(String filename, byte[] bytes) {}
}
