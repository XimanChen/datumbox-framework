/**
 * Copyright (C) 2013-2015 Vasilis Vryniotis <bbriniotis at datumbox.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.datumbox.framework.machinelearning.regression;

import com.datumbox.framework.machinelearning.common.interfaces.StepwiseCompatible;
import com.datumbox.common.dataobjects.Dataset;
import com.datumbox.common.persistentstorage.interfaces.DatabaseConfiguration;
import com.datumbox.common.persistentstorage.interfaces.DatabaseConnector;
import com.datumbox.common.utilities.DeepCopy;
import com.datumbox.common.utilities.MapFunctions;
import com.datumbox.framework.machinelearning.common.bases.mlmodels.BaseMLmodel;
import com.datumbox.framework.machinelearning.common.bases.mlmodels.BaseMLregressor;
import java.util.Map;


/**
 *
 * @author Vasilis Vryniotis <bbriniotis at datumbox.com>
 */
public class StepwiseRegression extends BaseMLregressor<StepwiseRegression.ModelParameters, StepwiseRegression.TrainingParameters, BaseMLregressor.ValidationMetrics>  {
    
    private transient BaseMLregressor mlregressor = null;
    
    
    public static class ModelParameters extends BaseMLregressor.ModelParameters {

        public ModelParameters(DatabaseConnector dbc) {
            super(dbc);
        }
        
        //EMPTY Model parameters. It relies on the mlregressor DB instead        
    } 

    
    public static class TrainingParameters extends BaseMLregressor.TrainingParameters {
        
        //primitives/wrappers
        private Integer maxIterations = null;
        
        private Double aout = 0.05;
        
        
        //Classes
        private Class<? extends BaseMLregressor> regressionClass;

        //Parameter Objects
        private BaseMLregressor.TrainingParameters regressionTrainingParameters;

        //Field Getters/Setters
        public Class<? extends BaseMLregressor> getRegressionClass() {
            return regressionClass;
        }

        public Integer getMaxIterations() {
            return maxIterations;
        }

        public void setMaxIterations(Integer maxIterations) {
            this.maxIterations = maxIterations;
        }

        public Double getAout() {
            return aout;
        }

        public void setAout(Double aout) {
            this.aout = aout;
        }

        public void setRegressionClass(Class<? extends BaseMLregressor> regressionClass) {
            if(!StepwiseCompatible.class.isAssignableFrom(regressionClass)) {
                throw new RuntimeException("The regression model is not Stepwise Compatible as it does not calculates the pvalues of the features.");
            }
            this.regressionClass = regressionClass;
        }

        public BaseMLregressor.TrainingParameters getRegressionTrainingParameters() {
            return regressionTrainingParameters;
        }

        public void setRegressionTrainingParameters(BaseMLregressor.TrainingParameters regressionTrainingParameters) {
            this.regressionTrainingParameters = regressionTrainingParameters;
        }
        
    }


    /*
    The no ValidationMetrics Class here!!!!!! The algorithm fetches the
    validations metrics of the mlregressor and cast them to BaseMLregressor.ValidationMetrics.
    */
    //public static class ValidationMetrics extends BaseMLregressor.ValidationMetrics { }
    
    
    
    public StepwiseRegression(String dbName, DatabaseConfiguration dbConf) {
        super(dbName, dbConf, StepwiseRegression.ModelParameters.class, StepwiseRegression.TrainingParameters.class, StepwiseRegression.ValidationMetrics.class, null); //do not define a validator. pass null and overload the kcross validation method to validate with the mlregressor object
    } 

    
    @Override
    @SuppressWarnings("unchecked")
    public BaseMLregressor.ValidationMetrics kFoldCrossValidation(Dataset trainingData, TrainingParameters trainingParameters, int k) {
        throw new UnsupportedOperationException("K-fold Cross Validation is not supported. Run it directly to the wrapped regressor."); 
    }
    
    @Override
    protected BaseMLregressor.ValidationMetrics validateModel(Dataset validationData) {
        loadRegressor();
        
        return (BaseMLregressor.ValidationMetrics) mlregressor.validate(validationData);
    }

    @Override
    protected void _fit(Dataset trainingData) {
        //get the training parameters
        TrainingParameters trainingParameters = knowledgeBase.getTrainingParameters();
        
        Integer maxIterations = trainingParameters.getMaxIterations();
        if(maxIterations==null) {
            maxIterations = Integer.MAX_VALUE;
        }
        double aOut = trainingParameters.getAout();
        
        //copy data before starting
        Dataset copiedTrainingData = DeepCopy.<Dataset>cloneObject(trainingData);
        
        //backword elimination algorithm
        for(int iteration = 0; iteration<maxIterations ; ++iteration) {
            
            Map<Object, Double> pvalues = runRegression(copiedTrainingData);
            
            if(pvalues.isEmpty()) {
                break; //no more features
            }
            
            //fetch the feature with highest pvalue, excluding constant
            pvalues.remove(Dataset.constantColumnName);
            Map.Entry<Object, Double> maxPvalueEntry = MapFunctions.selectMaxKeyValue(pvalues);
            pvalues=null;
            
            if(maxPvalueEntry.getValue()<=aOut) {
                break; //nothing to remove, the highest pvalue is less than the aOut
            }
            
            copiedTrainingData.removeColumn(maxPvalueEntry.getKey());
            
            if(copiedTrainingData.getColumnSize()==0) {
                break; //if no more features exit
            }
        }
        
        ModelParameters modelParameters = knowledgeBase.getModelParameters();
        
        //set the only parameters that are inherited by the BaseMLregressor.MP abstract
        modelParameters.setD(copiedTrainingData.getColumnSize());
        modelParameters.setN(copiedTrainingData.size());        
        
        //once we have the dataset has been cleared from the unnecessary columns train the model once again
        mlregressor = BaseMLmodel.newInstance(trainingParameters.getRegressionClass(), dbName, knowledgeBase.getDbConf()); 
        
        mlregressor.fit(copiedTrainingData, trainingParameters.getRegressionTrainingParameters());
        copiedTrainingData = null;
    }

    @Override
    protected void predictDataset(Dataset newData) {
        loadRegressor();
        
        mlregressor.predict(newData);
    }
    
    @Override
    public void erase() {
        loadRegressor();
        mlregressor.erase();
        mlregressor = null;
        
        super.erase();
    }
    
    private void loadRegressor() {
        if(mlregressor==null) {
            //initialize algorithm
            mlregressor = BaseMLmodel.newInstance(knowledgeBase.getTrainingParameters().getRegressionClass(), dbName, knowledgeBase.getDbConf()); 
        }
    }
    
    private Map<Object, Double> runRegression(Dataset trainingData) {
        TrainingParameters trainingParameters = knowledgeBase.getTrainingParameters();
        
        //initialize algorithm
        mlregressor = BaseMLmodel.newInstance(trainingParameters.getRegressionClass(), dbName, knowledgeBase.getDbConf()); 

        //train the regressor
        mlregressor.fit(trainingData, trainingParameters.getRegressionTrainingParameters());

        //get pvalues
        Map<Object, Double> pvalues = ((StepwiseCompatible)mlregressor).getFeaturePvalues();
        mlregressor.erase();
        
        return pvalues;
    }
}
