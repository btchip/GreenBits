package com.greenaddress.greenbits.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.google.common.base.Function;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.greenaddress.greenbits.ConnectivityObservable;
import com.greenaddress.greenbits.GreenAddressApplication;

import org.bitcoinj.utils.MonetaryFormat;
import org.codehaus.jackson.map.MappingJsonFactory;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;

import javax.annotation.Nullable;


public class MainFragment extends Fragment implements Observer {
    public final static int P2SH_FORTIFIED_OUT = 10;
    private Float maxSize = null;
    private Float currentSize = null;
    private Float minSize = null;
    private Float initialY = null;
    private View rootView;
    private List<Transaction> currentList;
    private Observer curBalanceObserver;
    private int curSubaccount;

    private Transaction processGATransaction(final Map<String, Object> txJSON, final int curBlock) throws ParseException {

        final List eps = (List) txJSON.get("eps");
        final String txhash  = (String) txJSON.get("txhash");

        final String memo = txJSON.containsKey("memo")?
            (String) txJSON.get("memo"): null;

        final Integer blockHeight = txJSON.containsKey("block_height") && txJSON.get("block_height") != null?
                (int) txJSON.get("block_height"): null;

        String counterparty = null;
        long amount = 0;
        int type;
        for (int i = 0; i < eps.size(); ++i) {
            final Map<String, Object> ep = (Map<String, Object>) eps.get(i);
            if (ep.get("social_destination") != null) {
                Map<String, Object> social_destination = null;
                try {
                    social_destination = new MappingJsonFactory().getCodec().readValue(
                            (String) ep.get("social_destination"), Map.class);
                } catch (final IOException e) {
                    //e.printStackTrace();
                }

                if (social_destination != null) {
                    counterparty = social_destination.get("type").equals("voucher") ?
                            "Voucher" : (String) social_destination.get("name");
                } else {
                    counterparty = (String) ep.get("social_destination");
                }
            }
            if (((Boolean) ep.get("is_relevant")).booleanValue()) {
                if (((Boolean) ep.get("is_credit")).booleanValue()) {
                    final boolean external_social = ep.get("social_destination") != null &&
                            ((Number) ep.get("script_type")).intValue() != P2SH_FORTIFIED_OUT;
                    if (!external_social) {
                        amount += Long.valueOf((String) ep.get("value")).longValue();
                    }
                } else {
                    amount -= Long.valueOf((String) ep.get("value"));
                }
            }
        }
        if (amount >= 0) {
            type = Transaction.TYPE_IN;
            for (int i = 0; i < eps.size(); ++i) {
                final Map<String, Object> ep = (Map<String, Object>) eps.get(i);
                if (!((Boolean) ep.get("is_credit")).booleanValue() && ep.get("social_source") != null) {
                    counterparty = (String) ep.get("social_source");
                }
            }
        } else {
            final List<Map<String, Object>> recip_eps = new ArrayList<>();
            for (int i = 0; i < eps.size(); ++i) {
                final Map<String, Object> ep = (Map<String, Object>) eps.get(i);
                if (((Boolean) ep.get("is_credit")).booleanValue() &&
                        (!((Boolean) ep.get("is_relevant")).booleanValue() ||
                                ep.get("social_destination") != null)) {
                    recip_eps.add(ep);
                }
            }
            if (recip_eps.size() > 0) {
                type = Transaction.TYPE_OUT;
                if (counterparty == null) {
                    counterparty = (String) recip_eps.get(0).get("ad");
                }
                if (recip_eps.size() > 1) {
                    counterparty += ", ...";
                }
            } else {
                type = Transaction.TYPE_REDEPOSIT;
            }
        }
        return new Transaction(type, amount, counterparty,
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse((String) txJSON.get("created_at")), txhash, memo, curBlock, blockHeight);
    }

    private void updateBalance(final Activity activity) {
        final String btcUnit = (String) ((GreenAddressApplication) activity.getApplication()).gaService.getAppearanceValue("unit");
        final MonetaryFormat bitcoinFormat = CurrencyMapper.mapBtcUnitToFormat(btcUnit);
        final TextView balanceBitcoinIcon = (TextView) rootView.findViewById(R.id.mainBalanceBitcoinIcon);
        final TextView bitcoinScale = (TextView) rootView.findViewById(R.id.mainBitcoinScaleText);
        bitcoinScale.setText( Html.fromHtml(CurrencyMapper.mapBtcUnitToPrefix(btcUnit) ) );
        if (btcUnit == null || btcUnit.equals("bits")) {
            balanceBitcoinIcon.setText("bits ");
        } else {
            balanceBitcoinIcon.setText(Html.fromHtml("&#xf15a; "));
        }
        final String btcBalance = bitcoinFormat.noCode().format(
                ((GreenAddressApplication) activity.getApplication()).gaService.getBalanceCoin(curSubaccount)).toString();
        final String fiatBalance =
                MonetaryFormat.FIAT.minDecimals(2).noCode().format(
                        ((GreenAddressApplication) activity.getApplication()).gaService.getBalanceFiat(curSubaccount))
                        .toString();
        final String fiatCurrency = ((GreenAddressApplication) activity.getApplication()).gaService.getFiatCurrency();
        final String converted = CurrencyMapper.map(fiatCurrency);

        final TextView balanceText = (TextView) rootView.findViewById(R.id.mainBalanceText);
        final TextView balanceFiatText = (TextView) rootView.findViewById(R.id.mainLocalBalanceText);
        final FontAwesomeTextView balanceFiatIcon = (FontAwesomeTextView) rootView.findViewById(R.id.mainLocalBalanceIcon);
        final DecimalFormat formatter = new DecimalFormat("#,###.########");
        try {
            balanceText.setText(formatter.format(formatter.parse(btcBalance)));
        } catch (final ParseException e) {
            balanceText.setText(btcBalance);
        }

        try {
            balanceFiatText.setText(formatter.format(formatter.parse(fiatBalance)));

        } catch (final ParseException e) {
            balanceFiatText.setText(fiatBalance);
        }

        if (converted != null) {
            balanceFiatIcon.setText(Html.fromHtml(converted + " "));
            balanceFiatIcon.setAwesomeTypeface();
            balanceFiatIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        } else {
            balanceFiatIcon.setText(fiatCurrency);
            balanceFiatIcon.setDefaultTypeface();
            balanceFiatIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        }
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_main, container, false);
        curSubaccount = getActivity().getSharedPreferences("main", Context.MODE_PRIVATE).getInt("curSubaccount", 0);

        final TextView firstP = (TextView) rootView.findViewById(R.id.mainFirstParagraphText);
        final TextView secondP = (TextView) rootView.findViewById(R.id.mainSecondParagraphText);
        final TextView thirdP = (TextView) rootView.findViewById(R.id.mainThirdParagraphText);

        firstP.setMovementMethod(LinkMovementMethod.getInstance());
        secondP.setMovementMethod(LinkMovementMethod.getInstance());
        thirdP.setMovementMethod(LinkMovementMethod.getInstance());


        /* currentSize = balanceText.getTextSize();
        maxSize = currentSize;
        minSize = currentSize / 2.0f; */

        curBalanceObserver = makeBalanceObserver();
        ((GreenAddressApplication) getActivity().getApplication()).gaService.getBalanceObservables().get(new Long(curSubaccount)).addObserver(curBalanceObserver);

        if (((GreenAddressApplication) getActivity().getApplication()).gaService.getBalanceCoin(curSubaccount) != null) {
            updateBalance(getActivity());
        }

        final LinearLayout balanceLayout = (LinearLayout) rootView.findViewById(R.id.mainBalanceLayout);
//        listView.setOnTouchListener(new View.OnTouchListener() {
//            @Override
//            public boolean onTouch(View v, MotionEvent event) {
//                // Log.i("onTouch", " event=" + event );
//                switch (event.getAction()) {
//                    case MotionEvent.ACTION_DOWN:
//                        Log.i("onTouch", "Down");
//                        initialY = event.getY();
//                        break;
//                    case MotionEvent.ACTION_UP:
//                        Log.i("onTouch", "Up");
//                        break;
//                    case MotionEvent.ACTION_MOVE:
//                        float deltaY = event.getY() - initialY;
//
//                        if (Math.abs(deltaY) > 40) {
//                            initialY = event.getY();
//                            if (deltaY > 0)
//                                currentSize = Math.min(currentSize + 1.0f, maxSize);
//                            else
//                                currentSize = Math.max(currentSize - 1.0f, minSize);
//                            Log.i("onTouch", "current=" + currentSize + " minSize=" + minSize + " maxSize=" + maxSize);
//                            balanceText.setTextSize(currentSize);
//                            balanceBitcoinIcon.setTextSize(currentSize);
//                        }
//
//
//                        // LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) balanceLayout.getLayoutParams();
//                        // LinearLayout.LayoutParams newLayoutParams = new LinearLayout.LayoutParams(layoutParams.width,layoutParams.height-2);
//                        // balanceLayout.setLayoutParams(newLayoutParams);
//                        // balanceText.setTextSize(size);
//                        // size=size*1.1f;
//
//                }
//                return false;
//            }
//        });
        reloadTransactions(getActivity());

        ((GreenAddressApplication) getActivity().getApplication()).configureSubaccountsFooter(
                curSubaccount,
                getActivity(),
                (TextView) rootView.findViewById(R.id.sendAccountName),
                (LinearLayout) rootView.findViewById(R.id.mainFooter),
                (LinearLayout) rootView.findViewById(R.id.footerClickableArea),
                new Function<Integer, Void>() {
                    @Nullable
                    @Override
                    public Void apply(@Nullable Integer input) {
                        ((GreenAddressApplication) getActivity().getApplication()).gaService.getBalanceObservables().get(new Long(curSubaccount)).deleteObserver(curBalanceObserver);
                        curSubaccount = input;
                        curBalanceObserver = makeBalanceObserver();
                        ((GreenAddressApplication) getActivity().getApplication()).gaService.getBalanceObservables().get(new Long(curSubaccount)).addObserver(curBalanceObserver);
                        reloadTransactions(getActivity());
                        updateBalance(getActivity());

                        final SharedPreferences.Editor editor = getActivity().getSharedPreferences("main", Context.MODE_PRIVATE).edit();
                        editor.putInt("curSubaccount", curSubaccount);
                        editor.apply();

                        return null;
                    }
                }
        );
        return rootView;
    }

    private Observer makeBalanceObserver() {
        return new Observer() {
            @Override
            public void update(final Observable observable, final Object o) {
                final Activity activity = getActivity();
                if (activity != null) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateBalance(activity);
                            reloadTransactions(activity, true);  // newAdapter for unit change
                        }
                    });
                }
            }
        };
    }

    @Override
    public void onPause() {
        super.onPause();
        ((GreenAddressApplication) getActivity().getApplication()).gaService.getNewTransactionsObservable().deleteObserver(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        ((GreenAddressApplication) getActivity().getApplication()).gaService.getNewTransactionsObservable().addObserver(this);
    }

    private void reloadTransactions(final Activity activity) {
        reloadTransactions(activity, false);
    }



    private void reloadTransactions(final Activity activity, boolean newAdapter) {
        final ListView listView = (ListView) rootView.findViewById(R.id.mainTransactionList);
        final LinearLayout mainEmptyTransText = (LinearLayout) rootView.findViewById(R.id.mainEmptyTransText);
        final String btcUnit = (String) ((GreenAddressApplication) activity.getApplication()).gaService.getAppearanceValue("unit");

        if (currentList == null || newAdapter) {
            currentList = new ArrayList<>();
            listView.setAdapter(new ListTransactionsAdapter(activity, R.layout.list_element_transaction, currentList, btcUnit));
        }

        final ListenableFuture<Map<?, ?>> txFuture = ((GreenAddressApplication) activity.getApplication()).gaService.getMyTransactions(curSubaccount);

        Futures.addCallback(txFuture, new FutureCallback<Map<?, ?>>() {
            @Override
            public void onSuccess(@Nullable final Map<?, ?> result) {
                final List resultList = (List) result.get("list");

                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // "Make sure the content of your adapter is not modified from a background
                        //  thread, but only from the UI thread. Make sure your adapter calls
                        //  notifyDataSetChanged() when its content changes."

                        if(resultList!=null && resultList.size()>0) {
                            listView.setVisibility(View.VISIBLE);
                            mainEmptyTransText.setVisibility(View.GONE);
                        } else {
                            listView.setVisibility(View.GONE);
                            mainEmptyTransText.setVisibility(View.VISIBLE);
                        }

                        String oldFirstTxHash = null;
                        if (currentList.size() > 0) {
                            oldFirstTxHash = currentList.get(0).txhash;
                        }
                        currentList.clear();
                        for (int i = 0; i < resultList.size(); ++i) {
                            try {
                                currentList.add(processGATransaction((Map<String, Object>) resultList.get(i), (Integer) result.get("cur_block")));
                            } catch (final ParseException e) {
                                e.printStackTrace();
                            }
                        }
                        String newFirstTxHash = null;
                        final boolean scrollToTop;
                        if (currentList.size() > 0) {
                            newFirstTxHash = currentList.get(0).txhash;
                        }
                        if (oldFirstTxHash != null && newFirstTxHash != null) {
                            // scroll to top when new tx comes in
                            scrollToTop = !oldFirstTxHash.equals(newFirstTxHash);
                        } else {
                            scrollToTop = false;
                        }

                        ((ListTransactionsAdapter) listView.getAdapter()).notifyDataSetChanged();
                        if (scrollToTop) {
                            listView.smoothScrollToPosition(0);
                        }

                    }
                });

            }

            @Override
            public void onFailure(final Throwable t) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        listView.setVisibility(View.GONE);
                        mainEmptyTransText.setVisibility(View.VISIBLE);
                    }
                });

                }
            }, ((GreenAddressApplication) getActivity().getApplication()).gaService.es);
    }

    @Override
    public void update(final Observable observable, final Object data) {
        reloadTransactions(getActivity());
    }
}