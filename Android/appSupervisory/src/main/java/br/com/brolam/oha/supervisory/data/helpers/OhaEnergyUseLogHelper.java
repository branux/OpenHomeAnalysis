package br.com.brolam.oha.supervisory.data.helpers;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;

import java.util.ArrayList;
import java.util.Date;

import br.com.brolam.library.helpers.OhaHelper;
import br.com.brolam.oha.supervisory.data.OhaEnergyUseContract;
import br.com.brolam.oha.supervisory.data.OhaEnergyUseContract.EnergyUseLogEntry;
import br.com.brolam.oha.supervisory.data.OhaEnergyUseContract.EnergyUseLogEntry.FilterWatts;

import static br.com.brolam.oha.supervisory.data.OhaEnergyUseContract.CONTENT_URI_LOG;


/**
 * Recuperar os logs de utilização de energia e disponibilizar funcionalidades para calcular a utilização de energia
 * por Kwh e wh.
 *
 * @author Breno Marques
 * @version 1.00
 * @since Release 01
 */
public class OhaEnergyUseLogHelper implements LoaderManager.LoaderCallbacks<Cursor> {

    /**
     * Informar a utilização de energia por Wh e total por Kwh.
     */
    public class EnergyUseWh {
        public long dateTime;
        public double duration = 0, wh1 = 0, wh2 = 0, wh3 = 0, whTotal = 0;
    }

    ArrayList<EnergyUseWh> energyUseWhs;

    public interface IOhaEnergyUseLogHelper {
        LoaderManager getSupportLoaderManager();
        Context getContext();

        /**
         * Evento acionado após os calculos para disponibilizar as seguintes informações:
         * @param totalDuration duração em segundos.
         * @param totalWh1 total Watts por hora da fase 1.
         * @param totalWh2 total Watts por hora da fase 2.
         * @param totalWh3 total Watts por hora da fase 3.
         * @param totalWh total Watts por hora.
         * @param energyUseWhs informar uma lista com os totais por hora.
         * @param cursor cursor com todos os logs de utilização de energia.
         */
        void onCalculationCompleted(double totalDuration, double totalWh1, double totalWh2, double totalWh3, double totalWh, ArrayList<EnergyUseWh> energyUseWhs, Cursor cursor);
        void onBeginLoader();
        void onEndLoader();
    }

    IOhaEnergyUseLogHelper iOhaEnergyUseLogHelper;
    //Colunas para a projeção no Cursor:
    public static final String[] LOG_COLUMNS = new String[]{
            EnergyUseLogEntry._ID,
            EnergyUseLogEntry.COLUMN_DURATION,
            EnergyUseLogEntry.COLUMN_WATTS_1,
            EnergyUseLogEntry.COLUMN_WATTS_2,
            EnergyUseLogEntry.COLUMN_WATTS_3,
            EnergyUseLogEntry.COLUMN_WATTS_TOTAL
    };


    public static final int INDEX_LOG_DATE_TIME = 0;
    public static final int INDEX_DURATION = 1;
    public static final int INDEX_WATTS_1 = 2;
    public static final int INDEX_WATTS_2 = 3;
    public static final int INDEX_WATTS_3 = 4;
    public static final int INDEX_WATTS_TOTAL = 5;
    //Informar a hora de inicio e final para realizar a consulta no tabela de logs de utilização de energia.
    private long beginDateTime, endDateTime;
    private FilterWatts filterWatts;
    private double beginWatts = 0, endWatts = 0;

    public OhaEnergyUseLogHelper(IOhaEnergyUseLogHelper iOhaEnergyUseLogHelper, long beginDateTime, long endDateTime, FilterWatts filterWatts, double beginWatts, double endWatts) {
        this.iOhaEnergyUseLogHelper = iOhaEnergyUseLogHelper;
        this.beginDateTime = beginDateTime;
        this.endDateTime = endDateTime;
        this.energyUseWhs = new ArrayList<>();
        this.filterWatts = filterWatts;
        this.beginWatts = beginWatts;
        this.endWatts = endWatts;
        //Solicitar o carregamento do cursor com os logs de utilização de energia.
        this.iOhaEnergyUseLogHelper.getSupportLoaderManager().initLoader(0, null, this);
    }

    public void restartLoader(){
        this.iOhaEnergyUseLogHelper.getSupportLoaderManager().restartLoader(0, null, this);
    }

    /**
     * Ler o cursor com os logs de utilização de energia para realizar os calculos.
     */
    private void calculate(Cursor cursor) {
        double totalDuration = 0, totalWh1 = 0, totalWh2 = 0, totalWh3 = 0, totalWh = 0;
        this.energyUseWhs.clear();
        while (!cursor.isAfterLast()) {
            long dateTime = OhaHelper.getEndHour(new Date(cursor.getLong(INDEX_LOG_DATE_TIME)), false).getTime();
            EnergyUseWh energyUseWh = new EnergyUseWh();
            //Realizar os totais por hora:
            do {
                energyUseWh.duration += cursor.getDouble(INDEX_DURATION);
                energyUseWh.wh1 += getWh(cursor, INDEX_WATTS_1);
                energyUseWh.wh2 += getWh(cursor, INDEX_WATTS_2);
                energyUseWh.wh3 += getWh(cursor, INDEX_WATTS_3);
                energyUseWh.whTotal += getWh(cursor, INDEX_WATTS_TOTAL);
            }
            while (cursor.moveToNext() && cursor.getLong(INDEX_LOG_DATE_TIME) <= dateTime);
            //No final do calculo de cada hora, adicionar o total por hora a lista energyUseWhs:
            energyUseWh.dateTime = dateTime;
            this.energyUseWhs.add(energyUseWh);
            //Acumular o total por hora ao total geral:
            totalDuration += energyUseWh.duration;
            totalWh1 += energyUseWh.wh1;
            totalWh2 += energyUseWh.wh2;
            totalWh3 += energyUseWh.wh3;
            totalWh += energyUseWh.whTotal;
        }
        this.iOhaEnergyUseLogHelper.onCalculationCompleted(totalDuration, totalWh1, totalWh2, totalWh3, totalWh, this.energyUseWhs, cursor);
    }

    /**
     * Carregar o cursor conforme a hora inicial, final e {@link FilterWatts}
     */
    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        iOhaEnergyUseLogHelper.onBeginLoader();
        String selections;
        String[] selectionsArgs;
        switch (this.filterWatts) {
            case PHASE1:
            case PHASE2:
            case PHASE3:
            case TOTAL:
                selections = String.format("(%s BETWEEN ? AND ?) AND (%s BETWEEN ? AND ?)", EnergyUseLogEntry._ID, this.filterWatts.getFieldName());
                selectionsArgs = new String[]{Long.toString(this.beginDateTime), Long.toString(this.endDateTime), Double.toString(this.beginWatts), Double.toString(this.endWatts)};
                break;
            case NONE:
            default:
                selections = String.format("%s BETWEEN ? AND ?", EnergyUseLogEntry._ID);
                selectionsArgs = new String[]{Long.toString(this.beginDateTime), Long.toString(this.endDateTime)};
                break;
        }
        return new CursorLoader(
                this.iOhaEnergyUseLogHelper.getContext(),
                CONTENT_URI_LOG,
                LOG_COLUMNS,
                selections,
                selectionsArgs,
                String.format("%s ASC", EnergyUseLogEntry._ID)
        );
    }

    /**
     * Acionar o calculo após o carregamento do cursor.
     */
    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        try {
            if (data.moveToFirst()) {
                ContentResolver contentResolver = iOhaEnergyUseLogHelper.getContext().getContentResolver();
                data.setNotificationUri(contentResolver, OhaEnergyUseContract.CONTENT_URI_LOG);
                this.calculate(data);
            }
        } finally {
            iOhaEnergyUseLogHelper.onEndLoader();
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {

    }

    /**
     * Obter o total por Watts hora para um log de utilização de energia.
     * @param cursor informar o cursor posicionado no log onde deve ser calculado o Wh.
     * @param indexWatts informar o índice coluna, {@see LOG_COLUMNS}
     * @return watts por hora.
     */
    private double getWh(Cursor cursor, int indexWatts) {
        return (cursor.getDouble(indexWatts) * cursor.getDouble(INDEX_DURATION)) / 3600.00;
    }
}